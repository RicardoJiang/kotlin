/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.plugin.sandbox.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.typeFromQualifierParts
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class SupertypeWithArgumentGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {
    companion object {
        private val supertypeClassId = ClassId(FqName("foo"), Name.identifier("InterfaceWithArgument"))
        private val annotationClassId = ClassId.topLevel("SupertypeWithTypeArgument".fqn())
        private val PREDICATE = DeclarationPredicate.create { annotated(annotationClassId.asSingleFqName()) }

    }

    override fun computeAdditionalSupertypes(
        classLikeDeclaration: FirClassLikeDeclaration,
        resolvedSupertypes: List<FirResolvedTypeRef>,
        typeResolver: TypeResolveService
    ): List<ConeKotlinType> {
        if (resolvedSupertypes.any { it.coneType.classId == supertypeClassId }) return emptyList()

        val annotation = classLikeDeclaration.getAnnotationByClassId(annotationClassId, session) ?: return emptyList()
        val getClassArgument = (annotation as? FirAnnotationCall)?.argument as? FirGetClassCall ?: return emptyList()

        val resolvedArgument = typeFromQualifierParts(
            isMarkedNullable = false,
            source = classLikeDeclaration.source!!,
            typeResolver = typeResolver
        ) {
            fun visitQualifiers(expression: FirExpression) {
                if (expression !is FirPropertyAccessExpression) return
                expression.explicitReceiver?.let { visitQualifiers(it) }
                expression.qualifierName?.let { part(it) }
            }
            visitQualifiers(getClassArgument.argument)
        }

        return listOf(supertypeClassId.constructClassLikeType(arrayOf(resolvedArgument), isMarkedNullable = false))
    }

    private val FirPropertyAccessExpression.qualifierName: Name?
        get() = (calleeReference as? FirSimpleNamedReference)?.name

    override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
        return session.predicateBasedProvider.matches(PREDICATE, declaration)
    }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(PREDICATE)
    }
}
