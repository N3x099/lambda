package com.lambda.client.gui.clickgui

import com.google.gson.JsonParser
import com.lambda.client.LambdaMod
import com.lambda.client.gui.AbstractLambdaGui
import com.lambda.client.gui.clickgui.component.*
import com.lambda.client.gui.clickgui.window.ModuleSettingWindow
import com.lambda.client.gui.rgui.Component
import com.lambda.client.gui.rgui.windows.ListWindow
import com.lambda.client.module.AbstractModule
import com.lambda.client.module.ModuleManager
import com.lambda.client.module.modules.client.ClickGUI
import com.lambda.client.plugin.PluginManager
import com.lambda.client.setting.ConfigManager
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.commons.utils.ConnectionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.util.text.TextFormatting
import org.lwjgl.input.Keyboard
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

object LambdaClickGui : AbstractLambdaGui<ModuleSettingWindow, AbstractModule>() {

    private val windows = ArrayList<ListWindow>()
    var pluginWindow: ListWindow
    var remotePluginWindow: ListWindow
    var disabledRemotes = ArrayList<RemotePluginButton>()

    init {
        val allButtons = ModuleManager.modules
            .groupBy { it.category.displayName }
            .mapValues { (_, modules) -> modules.map { ModuleButton(it) } }

        var posX = 0.0f
        var posY = 0.0f
        val screenWidth = mc.displayWidth / ClickGUI.getScaleFactorFloat()

        /* Modules */
        for ((category, buttons) in allButtons) {
            val window = ListWindow(category, posX, posY, 90.0f, 300.0f, Component.SettingGroup.CLICK_GUI)

            window.children.addAll(buttons.customSort())
            windows.add(window)
            posX += 90.0f

            if (posX > screenWidth) {
                posX = 0.0f
                posY += 100.0f
            }
        }

        /* Plugins */
        pluginWindow = ListWindow("Plugins", posX, posY, 90.0f, 300.0f, Component.SettingGroup.CLICK_GUI)
        pluginWindow.children.add(ImportPluginButton)
        pluginWindow.children.add(DownloadPluginButton)
        windows.add(pluginWindow)

        posX += 90.0f

        remotePluginWindow = ListWindow("Remote plugins", posX, posY, 90.0f, 300.0f, Component.SettingGroup.CLICK_GUI)
        remotePluginWindow.visible = false
        windows.add(remotePluginWindow)

        populateRemotePlugins()

        windowList.addAll(windows)
    }

    override fun onDisplayed() {
        reorderModules()

        super.onDisplayed()
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        setModuleButtonVisibility { true }
        setPluginButtonVisibility { true }
        setRemotePluginButtonVisibility { true }
    }

    override fun newSettingWindow(element: AbstractModule, mousePos: Vec2f): ModuleSettingWindow {
        return ModuleSettingWindow(element, mousePos.x, mousePos.y)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == ClickGUI.bind.value.key && !searching && settingWindow?.listeningChild == null) {
            ClickGUI.disable()
        } else {
            super.keyTyped(typedChar, keyCode)

            val string = typedString.replace(" ", "")

            if (string.isNotEmpty()) {
                setModuleButtonVisibility { moduleButton ->
                    moduleButton.module.name.contains(string, true)
                        || moduleButton.module.alias.any { it.contains(string, true) }
                }
                setPluginButtonVisibility { pluginButton ->
                    pluginButton.name.contains(string, true)
                }
                setRemotePluginButtonVisibility { remotePluginButton ->
                    remotePluginButton.name.contains(string, true)
                }
            } else {
                setModuleButtonVisibility { true }
                setPluginButtonVisibility { true }
                setRemotePluginButtonVisibility { true }
            }
        }
    }

    private fun setModuleButtonVisibility(function: (ModuleButton) -> Boolean) {
        windowList.filterIsInstance<ListWindow>().forEach { window ->
            window.children.filterIsInstance<ModuleButton>().forEach { it.visible = function(it) }
        }
    }

    private fun setPluginButtonVisibility(function: (PluginButton) -> Boolean) {
        pluginWindow.children.filterIsInstance<PluginButton>().forEach { it.visible = function(it) }
    }

    private fun setRemotePluginButtonVisibility(function: (RemotePluginButton) -> Boolean) {
        remotePluginWindow.children.filterIsInstance<RemotePluginButton>().filter { it !in disabledRemotes }.forEach { it.visible = function(it) }
    }

    private fun populateRemotePlugins() {
        defaultScope.launch {
            try {
                val repoUrl = LambdaMod.GITHUB_API + "orgs/" + LambdaMod.PLUGIN_ORG + "/repos"
                val rawJson = ConnectionUtils.requestRawJsonFrom(repoUrl) {
                    LambdaMod.LOG.error("Failed to load organisation for plugins from GitHub", it)
                    throw it
                }

                LambdaMod.LOG.info("Requesting all public plugin repos from: $repoUrl")

                val pluginRepos = JsonParser().parse(rawJson).asJsonArray

                pluginRepos.forEach { pluginRepo ->
                    val releaseUrl = pluginRepo.asJsonObject.get("releases_url").asString.replace("{/id}", "")
                    val downloadsJson = ConnectionUtils.requestRawJsonFrom(pluginRepo.asJsonObject.get("releases_url").asString.replace("{/id}", "")) {
                        LambdaMod.LOG.error("Failed to load organisation for plugins from GitHub", it)
                        throw it
                    }

                    LambdaMod.LOG.info("Requesting details about: $releaseUrl")

                    val releases = JsonParser().parse(downloadsJson).asJsonArray
                    if (releases.size() > 0) {
                        releases[0]?.let { latestRelease ->
                            latestRelease.asJsonObject.get("assets").asJsonArray[0]?.let { firstAsset ->
                                val name = pluginRepo.asJsonObject.get("name").asString
                                if (!remotePluginWindow.containsName(name) &&
                                    !PluginManager.loadedPlugins.containsName(name)) {
                                    remotePluginWindow.children.add(
                                        RemotePluginButton(
                                            name,
                                            pluginRepo.asJsonObject.get("description").asString,
                                            latestRelease.asJsonObject.get("tag_name").asString,
                                            firstAsset.asJsonObject.get("browser_download_url").asString,
                                            firstAsset.asJsonObject.get("name").asString
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                LambdaMod.LOG.info("Found remote plugins: ${pluginRepos.size()}")
            } catch (e: Exception) {
                LambdaMod.LOG.error("Failed to parse plugin json", e)
            }
        }
    }

    fun updatePlugins() {
        PluginManager.getLoaders().filter {
            !PluginManager.loadedPluginLoader.containsName(it.name)
                && !pluginWindow.containsName(it.name)
        }.forEach {
            PluginManager.load(it)
        }
        pluginWindow.children.filterIsInstance<PluginButton>().filter { button ->
            PluginManager.getLoaders().none { button.name == it.name }
        }.forEach { button ->
            pluginWindow.children.remove(button)
            ConfigManager.save(button.plugin.config)
            PluginManager.unload(button.plugin)
            MessageSendHelper.sendChatMessage("[Plugin Manager] ${TextFormatting.GREEN}${button.name}${TextFormatting.RESET} removed.")

            remotePluginWindow.children.filter {
                it.name == button.name
            }.forEach {
                it.visible = true
                disabledRemotes.remove(it as RemotePluginButton)
            }
        }
    }

    fun downloadPlugin(remotePluginButton: RemotePluginButton) {
        defaultScope.launch(Dispatchers.IO) {
            MessageSendHelper.sendChatMessage("[Plugin Manager] Download of ${TextFormatting.GREEN}${remotePluginButton.name}${TextFormatting.RESET} has started...")
            try {
                // ToDo: Make it use the progress bar in button itself
                URL(remotePluginButton.downloadUrl).openStream().use { inputStream ->
                    Files.copy(inputStream, Paths.get("${PluginManager.pluginPath}/${remotePluginButton.fileName}"), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }

            remotePluginWindow.children.filter { remotePluginButton.name == it.name }.forEach {
                it.visible = false
                disabledRemotes.add(it as RemotePluginButton)
            }

            MessageSendHelper.sendChatMessage("[Plugin Manager] Download completed.")
            updatePlugins()
        }
    }

    fun toggleRemotePluginWindow() {
        remotePluginWindow.visible = !remotePluginWindow.visible
    }

    fun reorderModules() {
        val allButtons = ModuleManager.modules
            .groupBy { it.category.displayName }
            .mapValues { (_, modules) -> modules.map { ModuleButton(it) } }

        windows.filter { window ->
            window != pluginWindow
                && window != remotePluginWindow
        }.forEach { window ->
            window.children.clear()
            allButtons[window.name]?.let { window.children.addAll(it.customSort()) }
        }

        setModuleButtonVisibility { moduleButton ->
            moduleButton.module.name.contains(typedString, true)
                || moduleButton.module.alias.any { it.contains(typedString, true) }
        }
    }

    private fun List<ModuleButton>.customSort(): List<ModuleButton> {
        return when (ClickGUI.sortBy.value) {
            ClickGUI.SortByOptions.CUSTOM -> this.sortedByDescending { it.module.priorityForGui.value }
            ClickGUI.SortByOptions.FREQUENCY -> this.sortedByDescending { it.module.clicks.value }
            else -> this.sortedBy { it.name }
        }
    }
}