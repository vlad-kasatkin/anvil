package com.squareup.anvil.compiler.codegen

import com.squareup.anvil.compiler.AnvilCompilationException
import com.squareup.anvil.compiler.jvmSuppressWildcardsFqName
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.resolveClassByFqName
import org.jetbrains.kotlin.incremental.components.NoLookupLocation.FROM_BACKEND
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

private val kotlinAnnotations = listOf(jvmSuppressWildcardsFqName)

internal fun KtFile.classesAndInnerClasses(): Sequence<KtClassOrObject> {
  val children = findChildrenByClass(KtClassOrObject::class.java)

  return generateSequence(children.toList()) { list ->
    list
        .flatMap {
          it.declarations.filterIsInstance<KtClassOrObject>()
        }
        .ifEmpty { null }
  }.flatMap { it.asSequence() }
}

internal fun KtNamedDeclaration.requireFqName(): FqName = requireNotNull(fqName) {
  "fqName was null for $this, $nameAsSafeName"
}

internal fun KtAnnotated.isInterface(): Boolean = this is KtClass && this.isInterface()

internal fun KtAnnotated.hasAnnotation(fqName: FqName): Boolean {
  return findAnnotation(fqName) != null
}

internal fun KtAnnotated.findAnnotation(fqName: FqName): KtAnnotationEntry? {
  val annotationEntries = annotationEntries
  if (annotationEntries.isEmpty()) return null

  // Look first if it's a Kotlin annotation. These annotations are usually not imported and the
  // remaining checks would fail.
  annotationEntries.firstOrNull { annotation ->
    kotlinAnnotations
        .any { kotlinAnnotationFqName ->
          val text = annotation.text
          text.startsWith("@${kotlinAnnotationFqName.shortName()}") ||
              text.startsWith("@$kotlinAnnotationFqName")
        }
  }?.let { return it }

  // Check if the fully qualified name is used, e.g. `@dagger.Module`.
  val annotationEntry = annotationEntries.firstOrNull {
    it.text.startsWith("@${fqName.asString()}")
  }
  if (annotationEntry != null) return annotationEntry

  // Check if the simple name is used, e.g. `@Module`.
  val annotationEntryShort = annotationEntries
      .firstOrNull {
        it.shortName == fqName.shortName()
      }
      ?: return null

  val importPaths = containingKtFile.importDirectives.mapNotNull { it.importPath }

  // If the simple name is used, check that the annotation is imported.
  val hasImport = importPaths.any { it.fqName == fqName }
  if (hasImport) return annotationEntryShort

  // Look for star imports and make a guess.
  val hasStarImport = importPaths
      .filter { it.isAllUnder }
      .any {
        fqName.asString().startsWith(it.fqName.asString())
      }
  if (hasStarImport) return annotationEntryShort

  return null
}

internal fun KtClassOrObject.scope(
  annotationFqName: FqName,
  module: ModuleDescriptor
): FqName {
  return findScopeClassLiteralExpression(annotationFqName)
      .let {
        val children = it.children
        children.singleOrNull() ?: throw AnvilCompilationException(
            "Expected a single child, but had ${children.size} instead: ${it.text}",
            element = this
        )
      }
      .requireFqName(module)
}

private fun KtClassOrObject.findScopeClassLiteralExpression(
  annotationFqName: FqName
): KtClassLiteralExpression {
  val annotationEntry = findAnnotation(annotationFqName)
      ?: throw AnvilCompilationException(
          "Couldn't find $annotationFqName for Psi element: $text",
          element = this
      )

  val annotationValues = annotationEntry.valueArguments
      .asSequence()
      .filterIsInstance<KtValueArgument>()

  // First check if the is any named parameter. Named parameters allow a different order of
  // arguments.
  annotationValues
      .mapNotNull { valueArgument ->
        val children = valueArgument.children
        if (children.size == 2 && children[0] is KtValueArgumentName &&
            (children[0] as KtValueArgumentName).asName.asString() == "scope" &&
            children[1] is KtClassLiteralExpression
        ) {
          children[1] as KtClassLiteralExpression
        } else {
          null
        }
      }
      .firstOrNull()
      ?.let { return it }

  // If there is no named argument, then take the first argument, which must be a class literal
  // expression, e.g. @ContributesTo(Unit::class)
  return annotationValues
      .firstOrNull()
      ?.let { valueArgument ->
        valueArgument.children.firstOrNull() as? KtClassLiteralExpression
      }
      ?: throw AnvilCompilationException(
          "The first argument for $annotationFqName must be a class literal: $text",
          element = this
      )
}

internal fun PsiElement.fqNameOrNull(
  module: ModuleDescriptor
): FqName? {
  // Usually it's the opposite way, the require*() method calls the nullable method. But in this
  // case we'd like to preserve the better error messages in case something goes wrong.
  return try {
    requireFqName(module)
  } catch (e: AnvilCompilationException) {
    null
  }
}

internal fun PsiElement.requireFqName(
  module: ModuleDescriptor
): FqName {
  val containingKtFile = parentsWithSelf
      .filterIsInstance<KtClassOrObject>()
      .first()
      .containingKtFile

  fun failTypeHandling(): Nothing = throw AnvilCompilationException(
      "Don't know how to handle Psi element: $text",
      element = this
  )

  val classReference = when (this) {
    // If a fully qualified name is used, then we're done and don't need to do anything further.
    is KtDotQualifiedExpression -> return FqName(text)
    is KtNameReferenceExpression -> getReferencedName()
    is KtUserType -> referencedName ?: failTypeHandling()
    is KtTypeReference -> text
    is KtNullableType -> return innerType?.requireFqName(module) ?: failTypeHandling()
    else -> failTypeHandling()
  }

  // First look in the imports for the reference name. If the class is imported, then we know the
  // fully qualified name.
  containingKtFile.importDirectives
      .mapNotNull { it.importPath }
      .firstOrNull {
        it.fqName.shortName()
            .asString() == classReference
      }
      ?.let { return it.fqName }

  // If there is no import, then try to resolve the class with the same package as this file.
  module
      .resolveClassByFqName(
          FqName("${containingKtFile.packageFqName}.$classReference"),
          FROM_BACKEND
      )
      ?.let { return it.fqNameSafe }

  // If this doesn't work, then maybe a class from the Kotlin package is used.
  module.resolveClassByFqName(FqName("kotlin.$classReference"), FROM_BACKEND)
      ?.let { return it.fqNameSafe }

  // If this doesn't work, then maybe a class from the Kotlin package is used.
  module.resolveClassByFqName(FqName("kotlin.collections.$classReference"), FROM_BACKEND)
      ?.let { return it.fqNameSafe }

  // Everything else isn't supported.
  throw AnvilCompilationException(
      "Couldn't resolve FqName $classReference for Psi element: $text",
      element = this
  )
}

internal fun KtClassOrObject.functions(
  includeCompanionObjects: Boolean
): List<KtNamedFunction> {
  val elements = children.toMutableList()
  if (includeCompanionObjects) {
    elements += companionObjects.flatMap { it.children.toList() }
  }

  return elements
      .filterIsInstance<KtClassBody>()
      .flatMap { it.functions }
}

fun KtTypeReference.isNullable(): Boolean = typeElement is KtNullableType

fun KtTypeReference.isGenericType(): Boolean {
  val typeElement = typeElement ?: return false
  val children = typeElement.children

  if (children.size != 2) return false
  return children[1] is KtTypeArgumentList
}
