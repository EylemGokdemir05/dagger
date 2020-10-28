/*
 * Copyright (C) 2020 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.android.processor.internal.viewmodelinject

import com.google.auto.common.MoreElements
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import dagger.hilt.android.processor.internal.AndroidClassNames
import dagger.hilt.processor.internal.ClassNames
import dagger.hilt.processor.internal.Processors
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.util.ElementFilter
import javax.tools.Diagnostic

/**
 * Data class that represents a Hilt injected ViewModel
 */
internal class ViewModelInjectMetadata private constructor(
  val typeElement: TypeElement,
  val constructorElement: ExecutableElement
) {
  val className = ClassName.get(typeElement)

  val moduleClassName = ClassName.get(
    MoreElements.getPackage(typeElement).qualifiedName.toString(),
    "${className.simpleNames().joinToString("_")}_HiltModule"
  )

  val dependencyRequests = constructorElement.parameters.map { constructorArg ->
    constructorArg.toDependencyRequest()
  }

  companion object {
    internal fun create(
      processingEnv: ProcessingEnvironment,
      typeElement: TypeElement,
      constructorElement: ExecutableElement
    ): ViewModelInjectMetadata? {
      val types = processingEnv.typeUtils
      val elements = processingEnv.elementUtils
      val messager = processingEnv.messager

      var valid = true

      if (!types.isSubtype(
          typeElement.asType(),
          elements.getTypeElement(AndroidClassNames.VIEW_MODEL.toString()).asType()
        )
      ) {
        messager.error(
          "@ViewModelInject is only supported on types that subclass " +
            "${AndroidClassNames.VIEW_MODEL}."
        )
        valid = false
      }

      ElementFilter.constructorsIn(typeElement.enclosedElements).filter {
        Processors.hasAnnotation(it, AndroidClassNames.VIEW_MODEL_INJECT)
      }.let { constructors ->
        if (constructors.size > 1) {
          messager.error("Multiple @ViewModelInject annotated constructors found.", typeElement)
          valid = false
        }
        constructors.filter { it.modifiers.contains(Modifier.PRIVATE) }.forEach {
          messager.error("@ViewModelInject annotated constructors must not be private.", it)
          valid = false
        }
      }

      if (typeElement.nestingKind == NestingKind.MEMBER &&
        !typeElement.modifiers.contains(Modifier.STATIC)
      ) {
        messager.error(
          "@ViewModelInject may only be used on inner classes if they are static.",
          typeElement
        )
        valid = false
      }

      // Validate there is at most one SavedStateHandle constructor arg.
      constructorElement.parameters.filter {
        TypeName.get(it.asType()) == AndroidClassNames.SAVED_STATE_HANDLE
      }.apply {
        if (size > 1) {
          messager.error(
            "Expected zero or one constructor argument of type " +
              "${AndroidClassNames.SAVED_STATE_HANDLE}, found $size",
            constructorElement
          )
          valid = false
        }
      }

      if (!valid) return null

      return ViewModelInjectMetadata(
        typeElement,
        constructorElement
      )
    }

    private fun Messager.error(message: String, element: Element? = null) {
      printMessage(Diagnostic.Kind.ERROR, message, element)
    }
  }
}

/**
 * Data class that represents a binding request for an injected type.
 */
internal data class DependencyRequest(
  val name: String,
  val type: TypeName,
  val qualifier: AnnotationSpec? = null
)

internal fun VariableElement.toDependencyRequest(): DependencyRequest {
  val qualifier = annotationMirrors.find {
    Processors.hasAnnotation(it.annotationType.asElement(), ClassNames.QUALIFIER)
  }?.let { AnnotationSpec.get(it) }
  val type = TypeName.get(asType())
  return DependencyRequest(
    name = simpleName.toString(),
    type = type,
    qualifier = qualifier
  )
}
