package org.jetbrains.bazel.languages.starlark.formatting.configuration

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

private const val BUILDIFIER_ID = "Buildifier"

@Service(Service.Level.PROJECT)
@State(name = BUILDIFIER_ID)
data class BuildifierConfiguration(
  var enabledOnReformat: Boolean,
  var enabledOnSave: Boolean,
  var pathToExecutable: String?,
) : PersistentStateComponent<BuildifierConfiguration> {
  @Suppress("unused")
  constructor() : this(false, false, null) // Empty constructor required for state components

  override fun getState(): BuildifierConfiguration = this

  override fun loadState(state: BuildifierConfiguration) = XmlSerializerUtil.copyBean(state, this)

  companion object {
    val options = listOf("--lint=fix")

    fun getBuildifierConfiguration(project: Project): BuildifierConfiguration {
      val settings = project.getService(BuildifierConfiguration::class.java)
      return settings
    }
  }
}
