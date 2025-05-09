/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.*

context(context: CheckerContext, reporter: DiagnosticReporter)
fun checkMissingDependencySuperTypes(
    classifierType: ConeKotlinType?,
    source: KtSourceElement?,
): Boolean = checkMissingDependencySuperTypes(classifierType?.toSymbol(context.session), source, isEagerCheck = false)

context(context: CheckerContext, reporter: DiagnosticReporter)
fun checkMissingDependencySuperTypes(
    declaration: FirBasedSymbol<*>?,
    source: KtSourceElement?,
    isEagerCheck: Boolean,
): Boolean {
    if (declaration !is FirClassSymbol<*>) return false

    val missingSuperTypes = context.session.missingDependencyStorage.getMissingSuperTypes(declaration)
    val languageVersionSettings = context.languageVersionSettings
    for ((superType, origin) in missingSuperTypes) {
        val diagnostic =
            when {
                origin == FirMissingDependencyStorage.SupertypeOrigin.TYPE_ARGUMENT && !languageVersionSettings.supportsFeature(
                    LanguageFeature.ForbidUsingSupertypesWithInaccessibleContentInTypeArguments
                ) -> FirErrors.MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT
                isEagerCheck && !languageVersionSettings.supportsFeature(
                    LanguageFeature.AllowEagerSupertypeAccessibilityChecks
                ) -> FirErrors.MISSING_DEPENDENCY_SUPERCLASS_WARNING
                else -> FirErrors.MISSING_DEPENDENCY_SUPERCLASS
            }

        reporter.reportOn(
            source,
            diagnostic,
            superType.withArguments(emptyArray()).withNullability(nullable = false, context.session.typeContext),
            declaration.constructType(),
        )
    }

    return missingSuperTypes.isNotEmpty()
}
