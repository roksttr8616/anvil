@file:Suppress("invisible_reference", "invisible_member")
package com.squareup.anvil.compiler.codegen.ksp

import com.google.devtools.ksp.isDefault
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueArgument
import com.squareup.anvil.compiler.internal.daggerScopeFqName
import com.squareup.anvil.compiler.internal.mapKeyFqName
import com.squareup.anvil.compiler.qualifierFqName
import org.jetbrains.kotlin.name.FqName

internal fun <T : KSAnnotation> List<T>.checkNoDuplicateScopeAndBoundType(
  annotatedType: KSClassDeclaration
) {
  // Exit early to avoid allocating additional collections.
  if (size < 2) return
  if (size == 2 && this[0].scope() != this[1].scope()) return

  val duplicateScopes = groupBy { it.scope() }
    .filterValues { it.size > 1 }
    .ifEmpty { return }

  duplicateScopes.values.forEach { duplicateScopeAnnotations ->
    val duplicateBoundTypes = duplicateScopeAnnotations
      .groupBy { it.boundTypeOrNull() }
      .filterValues { it.size > 1 }
      .ifEmpty { return }
      .keys

    throw KspAnvilException(
      message = "${annotatedType.qualifiedName?.asString()} contributes multiple times to " +
        "the same scope using the same bound type: " +
        duplicateBoundTypes.joinToString(prefix = "[", postfix = "]") {
          it?.declaration?.simpleName?.getShortName() ?: annotatedType.superTypes.single()
            .resolve().declaration.simpleName.getShortName()
        } +
        ". Contributing multiple times to the same scope with the same bound type is forbidden " +
        "and all scope - bound type combinations must be distinct.",
      annotatedType
    )
  }
}

internal fun KSAnnotation.scope(): KSType =
  scopeOrNull()
    ?: throw KspAnvilException(
      message = "Couldn't find scope for ${annotationType.resolve().declaration.qualifiedName}.",
      this,
    )

internal fun KSAnnotation.scopeOrNull(): KSType? {
  return argumentAt("scope")?.value as? KSType?
}

internal fun KSAnnotation.boundTypeOrNull(): KSType? = argumentAt("boundType")?.value as? KSType?

internal fun KSAnnotation.argumentAt(
  name: String,
): KSValueArgument? {
  arguments
  return arguments.find { it.name?.asString() == name }
    ?.takeUnless { it.isDefault() }
}

private fun KSAnnotation.isTypeAnnotatedWith(
  annotationFqName: FqName
): Boolean = annotationType.resolve()
  .declaration
  .isAnnotationPresent(annotationFqName.asString())

internal fun KSAnnotation.isQualifier(): Boolean = isTypeAnnotatedWith(qualifierFqName)
internal fun KSAnnotation.isMapKey(): Boolean = isTypeAnnotatedWith(mapKeyFqName)
internal fun KSAnnotation.isDaggerScope(): Boolean = isTypeAnnotatedWith(daggerScopeFqName)
