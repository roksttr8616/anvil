package com.squareup.anvil.compiler.internal.reference

import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.internal.asFunctionType
import com.squareup.anvil.compiler.internal.asTypeNameOrNull
import com.squareup.anvil.compiler.internal.classDescriptorOrNull
import com.squareup.anvil.compiler.internal.fqNameOrNull
import com.squareup.anvil.compiler.internal.reference.ParameterReference.Descriptor
import com.squareup.anvil.compiler.internal.reference.ParameterReference.Psi
import com.squareup.anvil.compiler.internal.requireFqName
import com.squareup.anvil.compiler.internal.requireTypeName
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.TypeName
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.psi.KtParameter
import kotlin.LazyThreadSafetyMode.NONE

@ExperimentalAnvilApi
public sealed class ParameterReference {

  public abstract val name: String
  public abstract val declaringFunction: FunctionReference
  public val module: AnvilModuleDescriptor get() = declaringFunction.module

  public abstract val annotations: List<AnnotationReference>

  /**
   * The type can be null for generic type parameters like `T`. In this case try to resolve the
   * type with [resolveGenericTypeOrNull].
   */
  public abstract fun typeOrNull(): ClassReference?
  public fun type(): ClassReference = typeOrNull()
    ?: throw AnvilCompilationExceptionParameterReference(
      parameterReference = this,
      message = "Unable to get type for the parameter with name $name of " +
        "function ${declaringFunction.fqName}"
    )

  public abstract fun resolveGenericTypeOrNull(
    implementingClass: ClassReference.Psi
  ): ClassReference?

  public fun resolveGenericType(implementingClass: ClassReference.Psi): ClassReference =
    resolveGenericTypeOrNull(implementingClass)
      ?: throw AnvilCompilationExceptionParameterReference(
        parameterReference = this,
        message = "Unable to resolve type for the parameter with name $name with the " +
          "implementing class ${implementingClass.fqName}."
      )

  public abstract fun resolveTypeNameOrNull(
    implementingClass: ClassReference.Psi
  ): TypeName?

  public fun resolveTypeName(implementingClass: ClassReference.Psi): TypeName =
    resolveTypeNameOrNull(implementingClass)
      ?: throw AnvilCompilationExceptionParameterReference(
        message = "Unable to resolve type name for parameter with name $name with the " +
          "implementing class ${implementingClass.fqName}.",
        parameterReference = this
      )

  override fun toString(): String {
    return "${this::class.qualifiedName}(${declaringFunction.fqName}(.., $name,..))"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ParameterReference) return false

    if (name != other.name) return false
    if (declaringFunction != other.declaringFunction) return false

    return true
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + declaringFunction.hashCode()
    return result
  }

  public class Psi(
    public val parameter: KtParameter,
    override val declaringFunction: FunctionReference.Psi
  ) : ParameterReference() {
    override val name: String = parameter.nameAsSafeName.asString()

    override val annotations: List<AnnotationReference.Psi> by lazy(NONE) {
      parameter.annotationEntries.map {
        it.toAnnotationReference(declaringClass = null, module)
      }
    }

    override fun typeOrNull(): ClassReference? {
      return parameter.typeReference
        ?.let { declaringFunction.declaringClass.resolveTypeReference(it) }
        ?.requireFqName(module)
        ?.toClassReference(module)
    }

    override fun resolveGenericTypeOrNull(implementingClass: ClassReference.Psi): ClassReference? {
      typeOrNull()?.let { return it }

      val typeReference = parameter.typeReference
        ?.let { typeReference ->
          implementingClass.resolveTypeReference(typeReference)
        }
        ?: parameter.typeReference

      return typeReference?.fqNameOrNull(module)?.toClassReference(module)
    }

    override fun resolveTypeNameOrNull(implementingClass: ClassReference.Psi): TypeName? {
      return parameter.typeReference
        ?.let { typeReference ->
          implementingClass.resolveTypeReference(typeReference)
            ?: typeReference
        }
        ?.requireTypeName(module)
        ?.let { typeName ->
          // This is a special case when mixing parsing between descriptors and PSI.  Descriptors
          // will always represent lambda types as kotlin.Function* types, so lambda arguments
          // must be converted or their signatures won't match.
          // see https://github.com/square/anvil/issues/400
          (typeName as? LambdaTypeName)?.asFunctionType()
            ?: typeName
        }
    }
  }

  public class Descriptor(
    public val parameter: ValueParameterDescriptor,
    override val declaringFunction: FunctionReference
  ) : ParameterReference() {
    override val name: String = parameter.name.asString()

    override val annotations: List<AnnotationReference.Descriptor> by lazy(NONE) {
      parameter.annotations.map {
        it.toAnnotationReference(declaringClass = null, module)
      }
    }

    override fun typeOrNull(): ClassReference? {
      return parameter.type.classDescriptorOrNull()?.toClassReference(declaringFunction.module)
    }

    override fun resolveGenericTypeOrNull(implementingClass: ClassReference.Psi): ClassReference? {
      typeOrNull()?.let { return it }

      return implementingClass
        .resolveGenericKotlinTypeOrNull(declaringFunction.declaringClass, parameter.type)
        ?.fqNameOrNull(module)
        ?.toClassReferenceOrNull(module)
    }

    override fun resolveTypeNameOrNull(implementingClass: ClassReference.Psi): TypeName? {
      return parameter.type
        .let {
          if (it.classDescriptorOrNull() != null) {
            // Then it's a normal type and not a generic type.
            it.asTypeNameOrNull()
          } else {
            null
          }
        }
        ?: implementingClass.resolveGenericKotlinTypeOrNull(
          declaringClass = declaringFunction.declaringClass,
          typeToResolve = parameter.type
        )?.requireTypeName(module)
        ?: parameter.type.asTypeNameOrNull()
    }
  }
}

@ExperimentalAnvilApi
public fun KtParameter.toParameterReference(
  declaringFunction: FunctionReference.Psi
): Psi {
  return Psi(this, declaringFunction)
}

@ExperimentalAnvilApi
public fun ValueParameterDescriptor.toParameterReference(
  declaringFunction: FunctionReference.Descriptor
): Descriptor {
  return Descriptor(this, declaringFunction)
}

@ExperimentalAnvilApi
@Suppress("FunctionName")
public fun AnvilCompilationExceptionParameterReference(
  parameterReference: ParameterReference,
  message: String,
  cause: Throwable? = null
): AnvilCompilationException = when (parameterReference) {
  is Psi -> AnvilCompilationException(
    element = parameterReference.parameter,
    message = message,
    cause = cause
  )
  is Descriptor -> AnvilCompilationException(
    parameterDescriptor = parameterReference.parameter,
    message = message,
    cause = cause
  )
}