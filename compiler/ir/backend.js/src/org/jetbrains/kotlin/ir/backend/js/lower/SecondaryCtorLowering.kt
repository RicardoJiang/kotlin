/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.backend.common.ir.ValueRemapper
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.constructorFactory
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrRawFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrTransformer
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull

/**
 * Generates static functions for each secondary constructor.
 */
class SecondaryConstructorLowering(val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (context.es6mode) return null

        if (declaration is IrConstructor && !declaration.isPrimary) {
            val irClass = declaration.parentAsClass

            if (context.inlineClassesUtils.isClassInlineLike(irClass)) return null

            return transformConstructor(declaration, irClass)
        }

        return null
    }

    private fun transformConstructor(constructor: IrConstructor, irClass: IrClass): List<IrSimpleFunction> {
        val delegate = context.buildConstructorDelegate(constructor, irClass)

        val factory = context.buildConstructorFactory(constructor, irClass)

        generateStubsBody(constructor, irClass, delegate, factory)

        return listOf(delegate, factory)
    }

    private fun generateStubsBody(constructor: IrConstructor, irClass: IrClass, delegate: IrSimpleFunction, factory: IrSimpleFunction) {
        // We should split secondary constructor into two functions,
        //   *  Initializer which contains constructor's body and takes just created object as implicit param `$this`
        //   **   This function is also delegation constructor
        //   *  Creation function which has same signature with original constructor,
        //      creates new object via `Object.create` builtIn and passes it to corresponding `Init` function
        // In other words:
        // Foo::constructor(...) {
        //   body
        // }
        // =>
        // Foo_init_$Init$(..., $this) {
        //   body[ this = $this ]
        //   return $this
        // }
        // Foo_init_$Create$(...) {
        //   val t = Object.create(Foo.prototype);
        //   return Foo_init_$Init$(..., t)
        // }
        generateInitBody(constructor, irClass, delegate)
        generateFactoryBody(irClass, factory, delegate)
    }

    private fun generateFactoryBody(irClass: IrClass, stub: IrSimpleFunction, delegate: IrSimpleFunction) {
        stub.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
            val type = irClass.defaultType
            val createFunctionIntrinsic = context.intrinsics.jsObjectCreateSymbol
            val irCreateCall = JsIrBuilder.buildCall(createFunctionIntrinsic, type, listOf(type))
            val irDelegateCall = JsIrBuilder.buildCall(delegate.symbol, type).also { call ->
                for (i in 0 until stub.typeParameters.size) {
                    call.typeArguments[i] = stub.typeParameters[i].toIrType()
                }

                call.arguments.assignFrom(stub.parameters) { JsIrBuilder.buildGetValue(it.symbol) }
                call.arguments.add(irCreateCall)
            }

            if (irClass.isSubclassOf(context.irBuiltIns.throwableClass.owner)) {
                val tmp = JsIrBuilder.buildVar(
                    type = irDelegateCall.type,
                    parent = stub,
                    initializer = irDelegateCall
                )

                statements += tmp
                statements += JsIrBuilder.buildCall(context.intrinsics.captureStack).also { call ->
                    call.arguments[0] = JsIrBuilder.buildGetValue(tmp.symbol)
                    call.arguments[1] =
                        IrRawFunctionReferenceImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.anyType, stub.symbol)
                }
                statements += JsIrBuilder.buildReturn(stub.symbol, JsIrBuilder.buildGetValue(tmp.symbol), context.irBuiltIns.nothingType)
            } else {
                val irReturn = JsIrBuilder.buildReturn(stub.symbol, irDelegateCall, context.irBuiltIns.nothingType)
                statements += irReturn
            }

        }
    }

    private fun generateInitBody(constructor: IrConstructor, irClass: IrClass, delegate: IrSimpleFunction) {
        val thisParam = delegate.parameters.last()
        val oldThisReceiver = irClass.thisReceiver!!
        val constructorBody = constructor.body

        // TODO: replace parameters as well
        if (constructorBody != null) {
            delegate.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
                statements += (constructorBody.deepCopyWithSymbols(delegate) as IrStatementContainer).statements
                statements += JsIrBuilder.buildReturn(
                    delegate.symbol,
                    JsIrBuilder.buildGetValue(thisParam.symbol),
                    context.irBuiltIns.nothingType
                )
                transformChildrenVoid(
                    ThisUsageReplaceTransformer(
                        constructor.symbol,
                        delegate.symbol,
                        (constructor.parameters + oldThisReceiver)
                            .zip(delegate.parameters)
                            .associate { (old, new) -> old.symbol to new.symbol }
                    )
                )
            }
        }
    }

    private class ThisUsageReplaceTransformer(
        val constructor: IrConstructorSymbol,
        val function: IrFunctionSymbol,
        symbolMapping: Map<IrValueSymbol, IrValueSymbol>
    ) : ValueRemapper(symbolMapping) {
        private val newThisSymbol = symbolMapping.values.last()

        override fun visitReturn(expression: IrReturn): IrExpression =
            if (expression.returnTargetSymbol != constructor)
                super.visitReturn(expression)
            else
                IrReturnImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    function,
                    IrGetValueImpl(expression.startOffset, expression.endOffset, newThisSymbol.owner.type, newThisSymbol)
                )
    }
}

private fun IrTypeParameter.toIrType() = IrSimpleTypeImpl(symbol, true, emptyList(), emptyList())

private fun JsIrBackendContext.buildInitDeclaration(constructor: IrConstructor, irClass: IrClass): IrSimpleFunction {
    val type = irClass.defaultType
    val constructorName = "${irClass.name}_init"
    val functionName = "${constructorName}_\$Init\$"

    return irFactory.buildFun {
        startOffset = constructor.startOffset
        endOffset = constructor.endOffset
        name = Name.identifier(functionName)
        returnType = type
        visibility = DescriptorVisibilities.INTERNAL
        modality = Modality.FINAL
        isInline = constructor.isInline
        isExternal = constructor.isExternal
        origin = JsIrBuilder.SYNTHESIZED_DECLARATION
    }.also { initFunction ->
        initFunction.parent = constructor.parent
        initFunction.copyTypeParametersFrom(constructor.parentAsClass)


        initFunction.parameters = buildList {
            constructor.parameters.mapTo(this) { p -> p.copyTo(initFunction) }
            add(JsIrBuilder.buildValueParameter(initFunction, "\$this", type))
        }
    }
}

private fun JsIrBackendContext.buildFactoryDeclaration(constructor: IrConstructor, irClass: IrClass): IrSimpleFunction {
    val type = irClass.defaultType
    val constructorName = "${irClass.name}_init"
    val functionName = "${constructorName}_\$Create\$"

    return irFactory.buildFun {
        startOffset = constructor.startOffset
        endOffset = constructor.endOffset
        name = Name.identifier(functionName)
        returnType = type
        visibility = constructor.visibility
        modality = Modality.FINAL
        isInline = constructor.isInline
        isExternal = constructor.isExternal
    }.also { factory ->
        factory.parent = constructor.parent
        factory.copyTypeParametersFrom(constructor.parentAsClass)
        factory.parameters = constructor.parameters.map { p -> p.copyTo(factory) }
        factory.annotations = constructor.annotations
    }
}

private var IrConstructor.secondaryConstructorDelegate: IrSimpleFunction? by irAttribute(copyByDefault = false)

private fun JsIrBackendContext.buildConstructorDelegate(constructor: IrConstructor, klass: IrClass): IrSimpleFunction {
    return constructor::secondaryConstructorDelegate.getOrSetIfNull {
        buildInitDeclaration(constructor, klass)
    }
}

private fun JsIrBackendContext.buildConstructorFactory(constructor: IrConstructor, klass: IrClass): IrSimpleFunction {
    return constructor::constructorFactory.getOrSetIfNull {
        buildFactoryDeclaration(constructor, klass)
    }
}

/**
 * Replaces usages of secondary constructor with the corresponding static functions.
 */
class SecondaryFactoryInjectorLowering(val context: JsIrBackendContext) : BodyLoweringPass {

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (context.es6mode) return
        // TODO Simplify? Is this needed at all?
        var parentFunction: IrFunction? = container as? IrFunction
        var declaration = container
        while (parentFunction == null) {
            val parent = declaration.parent

            if (parent is IrFunction) {
                parentFunction = parent
            }

            declaration = parent as? IrDeclaration ?: break
        }

        irBody.accept(CallsiteRedirectionTransformer(context), parentFunction)
    }
}

private class CallsiteRedirectionTransformer(private val context: JsIrBackendContext) : IrTransformer<IrFunction?>() {

    private val defaultThrowableConstructor = context.defaultThrowableCtor

    private val IrConstructor.isSecondaryConstructorCall
        get() =
            !isPrimary && this != defaultThrowableConstructor && !isExternal && !context.inlineClassesUtils.isClassInlineLike(parentAsClass)

    override fun visitFunction(declaration: IrFunction, data: IrFunction?): IrStatement = super.visitFunction(declaration, declaration)

    override fun visitConstructorCall(expression: IrConstructorCall, data: IrFunction?): IrFunctionAccessExpression {
        super.visitConstructorCall(expression, data)

        val target = expression.symbol.owner
        return if (target.isSecondaryConstructorCall) {
            val factory = context.buildConstructorFactory(target, target.parentAsClass)
            replaceSecondaryConstructorWithFactoryFunction(expression, factory.symbol)
        } else expression
    }

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: IrFunction?): IrFunctionAccessExpression {
        super.visitDelegatingConstructorCall(expression, data)

        val target = expression.symbol.owner

        return if (target.isSecondaryConstructorCall) {
            val klass = target.parentAsClass
            val delegate = context.buildConstructorDelegate(target, klass)
            val newCall = replaceSecondaryConstructorWithFactoryFunction(expression, delegate.symbol)

            val readThis = expression.run {
                when (data) {
                    is IrConstructor -> {
                        val thisReceiver = data.constructedClass.thisReceiver!!
                        IrGetValueImpl(startOffset, endOffset, thisReceiver.type, thisReceiver.symbol)
                    }
                    is IrSimpleFunction -> {
                        val lastValueParameter = data.parameters.last()
                        IrGetValueImpl(startOffset, endOffset, lastValueParameter.type, lastValueParameter.symbol)
                    }
                    null -> compilationException("Parent function can't be null", expression)
                }
            }

            newCall.apply { arguments.add(readThis) }
        } else expression
    }

    private fun replaceSecondaryConstructorWithFactoryFunction(
        call: IrFunctionAccessExpression,
        newTarget: IrSimpleFunctionSymbol
    ): IrCall {
        val irClass = call.symbol.owner.parentAsClass
        return IrCallImpl(
            call.startOffset, call.endOffset, call.type, newTarget,
            typeArgumentsCount = call.typeArguments.size,
            superQualifierSymbol = irClass.symbol.takeIf { context.es6mode && call.isSyntheticDelegatingReplacement }
        ).apply {
            copyTypeArgumentsFrom(call)
            arguments.assignFrom(call.arguments)
        }
    }
}
