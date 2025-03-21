package org.jetbrains.plugins.bsp.target

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.bsp.config.BspFeatureFlags
import org.jetbrains.plugins.bsp.config.rootDir
import org.jetbrains.plugins.bsp.magicmetamodel.impl.workspacemodel.ModuleDetails
import org.jetbrains.plugins.bsp.utils.safeCastToURI
import org.jetbrains.plugins.bsp.workspacemodel.entities.BuildTargetInfo
import org.jetbrains.plugins.bsp.workspacemodel.entities.JavaModule
import org.jetbrains.plugins.bsp.workspacemodel.entities.Library
import org.jetbrains.plugins.bsp.workspacemodel.entities.Module
import java.net.URI

public data class TemporaryTargetUtilsState(
  var idToTargetInfo: Map<String, BuildTargetInfoState> = emptyMap(),
  var moduleIdToBuildTargetId: Map<String, String> = emptyMap(),
  var fileToId: Map<String, List<String>> = emptyMap(),
  var libraries: List<LibraryState> = emptyList(),
)

// This whole service is very temporary, it will be removed in the following PR
@Service(Service.Level.PROJECT)
@State(
  name = "TemporaryTargetUtils",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class TemporaryTargetUtils : PersistentStateComponent<TemporaryTargetUtilsState> {
  var targetIdToTargetInfo: Map<BuildTargetIdentifier, BuildTargetInfo> = emptyMap()
    private set
  private var moduleIdToBuildTargetId: Map<String, BuildTargetIdentifier> = emptyMap()

  // we must use URI as comparing URI path strings is susceptible to errors.
  // e.g., file:/test and file:///test should be similar in the URI world
  var fileToTargetId: Map<URI, List<BuildTargetIdentifier>> = hashMapOf()
    private set
  private var libraries: List<Library> = emptyList()
  private var libraryModules: List<JavaModule> = emptyList()
  private var libraryModulesLookupTable: HashSet<String> = hashSetOf()

  private var listeners: List<(Boolean) -> Unit> = emptyList()

  fun saveTargets(
    targetIdToTargetInfo: Map<BuildTargetIdentifier, BuildTargetInfo>,
    targetIdToModuleEntity: Map<BuildTargetIdentifier, Module>,
    targetIdToModuleDetails: Map<BuildTargetIdentifier, ModuleDetails>,
    libraries: List<Library>,
    libraryModules: List<JavaModule>,
  ) {
    this.targetIdToTargetInfo = targetIdToTargetInfo
    moduleIdToBuildTargetId =
      targetIdToModuleEntity.entries.associate { (targetId, module) ->
        module.getModuleName() to targetId
      }
    fileToTargetId =
      targetIdToModuleDetails.values
        .flatMap { it.toPairsUrlToId() }
        .groupBy { it.first }
        .mapValues { it.value.map { pair -> pair.second } }
    this.libraries = libraries
    this.libraryModules = libraryModules
    this.libraryModulesLookupTable = createLibraryModulesLookupTable()
  }

  private fun ModuleDetails.toPairsUrlToId(): List<Pair<URI, BuildTargetIdentifier>> =
    sources.flatMap { sources ->
      sources.sources.mapNotNull { it.uri.processUriString().safeCastToURI() }.map { it to target.id }
    }

  private fun String.processUriString() = this.trimEnd('/')

  private fun createLibraryModulesLookupTable() = libraryModules.map { it.genericModuleInfo.name }.toHashSet()

  fun fireSyncListeners(targetListChanged: Boolean) {
    listeners.forEach { it(targetListChanged) }
  }

  fun registerSyncListener(listener: (targetListChanged: Boolean) -> Unit) {
    listeners += listener
  }

  fun allTargetIds(): List<BuildTargetIdentifier> = targetIdToTargetInfo.keys.toList()

  fun getTargetsForFile(file: VirtualFile, project: Project): List<BuildTargetIdentifier> =
    fileToTargetId[file.url.processUriString().safeCastToURI()]
      ?: getTargetsFromAncestorsForFile(file, project)

  private fun getTargetsFromAncestorsForFile(file: VirtualFile, project: Project): List<BuildTargetIdentifier> {
    return if (BspFeatureFlags.isRetrieveTargetsForFileFromAncestorsEnabled) {
      val rootDir = project.rootDir
      var iter = file.parent
      while (iter != null && VfsUtil.isAncestor(rootDir, iter, false)) {
        val key = iter.url.processUriString().safeCastToURI()
        if (key in fileToTargetId) return fileToTargetId[key]!!
        iter = iter.parent
      }
      emptyList()
    } else {
      emptyList()
    }
  }

  fun getTargetIdForModuleId(moduleId: String): BuildTargetIdentifier? = moduleIdToBuildTargetId[moduleId]

  fun getBuildTargetInfoForId(buildTargetIdentifier: BuildTargetIdentifier): BuildTargetInfo? = targetIdToTargetInfo[buildTargetIdentifier]

  fun getBuildTargetInfoForModule(module: com.intellij.openapi.module.Module) =
    getTargetIdForModuleId(module.name)?.let { getBuildTargetInfoForId(it) }

  fun getAllLibraries(): List<Library> = libraries

  fun isLibraryModule(name: String): Boolean = name in libraryModulesLookupTable

  fun getAllLibraryModules(): List<JavaModule> = libraryModules

  override fun getState(): TemporaryTargetUtilsState =
    TemporaryTargetUtilsState(
      idToTargetInfo = targetIdToTargetInfo.mapKeys { it.key.uri }.mapValues { it.value.toState() },
      moduleIdToBuildTargetId = moduleIdToBuildTargetId.mapValues { it.value.uri },
      fileToId = fileToTargetId.mapKeys { o -> o.key.toString() }.mapValues { o -> o.value.map { it.uri } },
      libraries = libraries.map { it.toState() },
    )

  override fun loadState(state: TemporaryTargetUtilsState) {
    targetIdToTargetInfo =
      state.idToTargetInfo
        .mapKeys { BuildTargetIdentifier(it.key) }
        .mapValues { it.value.fromState() }
    moduleIdToBuildTargetId = state.moduleIdToBuildTargetId.mapValues { BuildTargetIdentifier(it.value) }
    fileToTargetId =
      state.fileToId.mapKeys { o -> o.key.safeCastToURI() }.mapValues { o -> o.value.map { BuildTargetIdentifier(it) } }
    libraries = state.libraries.map { it.fromState() }
  }
}

val Project.temporaryTargetUtils: TemporaryTargetUtils
  get() = service<TemporaryTargetUtils>()
