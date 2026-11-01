package cn.mythicland.dreamchest;

import cn.mythicland.framework.bukkit.command.CommandBinding;
import cn.mythicland.framework.bukkit.command.CommandContext;
import cn.mythicland.framework.bukkit.command.CommandRoute;
import cn.mythicland.framework.bukkit.text.LegacyText;

import java.util.Objects;

/**
 * Handles DreamChest administrative commands.
 */
@CommandBinding("dreamchest")
final class DreamChestCommand {

    private final DreamChestPlugin plugin;

    DreamChestCommand(DreamChestPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @CommandRoute(path = {"dreamchest", "reload"}, maxArguments = Integer.MAX_VALUE, permission = "dreamchest.reload")
    void reload(CommandContext context) {
        context.requireArguments(0);
        plugin.reloadDreamChest();
        context.sender().sendMessage(LegacyText.colorize("&aDreamChest 配置已重新加载。"));
    }
}
