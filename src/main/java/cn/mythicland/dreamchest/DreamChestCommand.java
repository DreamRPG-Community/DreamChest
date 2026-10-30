package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.bootstrap.annotation.CommandComponent;
import cn.mythicland.dreamlib.bootstrap.annotation.CommandHandler;
import cn.mythicland.dreamlib.command.CommandContext;
import cn.mythicland.dreamlib.text.LegacyText;

import java.util.Objects;

/**
 * Handles DreamChest administrative commands.
 */
@CommandComponent("dreamchest")
final class DreamChestCommand {

    private final DreamChestPlugin plugin;

    DreamChestCommand(DreamChestPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @CommandHandler(value = "reload", permission = "dreamchest.reload")
    void reload(CommandContext context) {
        context.requireArguments(0);
        plugin.reloadDreamChest();
        context.sender().sendMessage(LegacyText.colorize("&aDreamChest 配置已重新加载。"));
    }
}
