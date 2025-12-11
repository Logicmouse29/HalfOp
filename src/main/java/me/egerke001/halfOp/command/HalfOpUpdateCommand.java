package me.egerke001.halfOp.command;

import me.egerke001.halfOp.HalfOp;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class HalfOpUpdateCommand implements CommandExecutor {

    private final HalfOp plugin;

    public HalfOpUpdateCommand(HalfOp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("halfop.update")) {
            sender.sendMessage("You do not have permission to run this command.");
            return true;
        }

        sender.sendMessage("Checking for HalfOp updates...");
        plugin.runUpdateCheck(sender);
        return true;
    }
}
