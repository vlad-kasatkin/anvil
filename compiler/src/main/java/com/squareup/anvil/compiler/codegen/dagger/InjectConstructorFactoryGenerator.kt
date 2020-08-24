package com.squareup.anvil.compiler.codegen.dagger

import com.squareup.anvil.compiler.codegen.CodeGenerator
import com.squareup.anvil.compiler.codegen.CodeGenerator.GeneratedFile
import com.squareup.anvil.compiler.codegen.classesAndInnerClasses
import com.squareup.anvil.compiler.codegen.hasAnnotation
import com.squareup.anvil.compiler.generateClassName
import com.squareup.anvil.compiler.injectFqName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.jvm.jvmStatic
import dagger.internal.Factory
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.allConstructors
import java.io.File

internal class InjectConstructorFactoryGenerator : CodeGenerator {
  override fun generateCode(
    codeGenDir: File,
    module: ModuleDescriptor,
    projectFiles: Collection<KtFile>
  ): Collection<GeneratedFile> {
    return projectFiles
        .asSequence()
        .flatMap { it.classesAndInnerClasses() }
        .mapNotNull { clazz ->
          clazz.allConstructors
              .singleOrNull { it.hasAnnotation(injectFqName) }
              ?.let {
                generateFactoryClass(codeGenDir, module, clazz, it)
              }
        }
        .toList()
  }

  @OptIn(ExperimentalStdlibApi::class)
  private fun generateFactoryClass(
    codeGenDir: File,
    module: ModuleDescriptor,
    clazz: KtClassOrObject,
    constructor: KtConstructor<*>
  ): GeneratedFile {
    val packageName = clazz.containingKtFile.packageFqName.asString()
    val className = "${clazz.generateClassName()}_Factory"
    val classType = clazz.asClassName()

    val parameters = constructor.getValueParameters().mapToParameter(module)

    val factoryClass = ClassName(packageName, className)

    val content = FileSpec.builder(packageName, className)
        .addType(
            TypeSpec
                .classBuilder(factoryClass)
                .addAnvilAnnotation()
                .addSuperinterface(Factory::class.asClassName().parameterizedBy(classType))
                .apply {
                  if (parameters.isNotEmpty()) {
                    primaryConstructor(
                        FunSpec.constructorBuilder()
                            .apply {
                              parameters.forEach { parameter ->
                                addParameter(parameter.name, parameter.providerTypeName)
                              }
                            }
                            .build()
                    )

                    parameters.forEach { parameter ->
                      addProperty(
                          PropertySpec.builder(parameter.name, parameter.providerTypeName)
                              .initializer(parameter.name)
                              .addModifiers(PRIVATE)
                              .build()
                      )
                    }
                  }
                }
                .addFunction(
                    FunSpec.builder("get")
                        .addModifiers(OVERRIDE)
                        .returns(classType)
                        .apply {
                          val argumentList = parameters.asArgumentList(
                              asProvider = true,
                              includeModule = false
                          )

                          addStatement("return newInstance($argumentList)")
                        }
                        .build()
                )
                .addType(
                    TypeSpec
                        .companionObjectBuilder()
                        .apply {
                          if (parameters.isEmpty()) {
                            addProperty(
                                PropertySpec.builder("instance", factoryClass)
                                    .addModifiers(PRIVATE)
                                    .initializer("%T()", factoryClass)
                                    .build()
                            )
                          }
                        }
                        .addFunction(
                            FunSpec.builder("create")
                                .jvmStatic()
                                .apply {
                                  if (parameters.isEmpty()) {
                                    addStatement("return instance")
                                  } else {
                                    parameters.forEach { parameter ->
                                      addParameter(parameter.name, parameter.providerTypeName)
                                    }

                                    val argumentList = parameters.asArgumentList(
                                        asProvider = false,
                                        includeModule = false
                                    )

                                    addStatement(
                                        "return %T($argumentList)",
                                        factoryClass
                                    )
                                  }
                                }
                                .returns(factoryClass)
                                .build()
                        )
                        .addFunction(
                            FunSpec.builder("newInstance")
                                .jvmStatic()
                                .apply {
                                  parameters.forEach { parameter ->
                                    addParameter(
                                        name = parameter.name,
                                        type = parameter.originalTypeName
                                    )
                                  }
                                  val argumentsWithoutModule = parameters.joinToString { it.name }

                                  addStatement("return %T($argumentsWithoutModule)", classType)
                                }
                                .returns(classType)
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .build()
        .writeToString()
        .replaceImports(clazz)

    val directory = File(codeGenDir, packageName.replace('.', File.separatorChar))
    val file = File(directory, "$className.kt")
    check(file.parentFile.exists() || file.parentFile.mkdirs()) {
      "Could not generate package directory: ${file.parentFile}"
    }
    file.writeText(content)

    return GeneratedFile(file, content)
  }
}
