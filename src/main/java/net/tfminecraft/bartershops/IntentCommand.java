package net.tfminecraft.bartershops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;

/**
 * {@code /bartershops intent <shopId> [clear [--confirm]]}: staff inspect and clear the unfinished
 * trade that keeps a shop closed. Only a prepared trade that moved no money clears without
 * {@code --confirm}; every other state may already have moved money or items, so staff settle it by
 * hand first. Nothing clears while the ledger is not ready.
 */
final class IntentCommand implements CommandExecutor, TabCompleter {
    static final String USAGE = "§eUsage: /bartershops intent <shopId> [clear [--confirm]]";
    private static final String UNREADABLE = "unreadable";

    enum Clear { YES, CONFIRM, LEDGER }

    private final ShopTrades trades;
    private final Path auditFile;
    private final Logger logger;

    IntentCommand(ShopTrades trades, Path auditFile, Logger logger) {
        this.trades = trades;
        this.auditFile = auditFile;
        this.logger = logger;
    }

    /** Whether a trade may be cleared: the table in the BarterShops guide. */
    static Clear clearable(String state, Optional<Result> posting, boolean ledgerReady) {
        if (!ledgerReady) return Clear.LEDGER;
        boolean noMoney = posting.isEmpty() || posting.get() == Result.REFUSED;
        return "prepared".equals(state) && noMoney ? Clear.YES : Clear.CONFIRM;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ShopEvents.ADMIN_PERMISSION)) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }
        boolean clear = args.length >= 3 && args[2].equalsIgnoreCase("clear");
        boolean confirm = clear && args.length == 4 && args[3].equalsIgnoreCase("--confirm");
        if (args.length < 2 || !args[0].equalsIgnoreCase("intent") || (args.length >= 3 && !clear)
                || (args.length == 4 && !confirm) || args.length > 4) {
            sender.sendMessage(USAGE);
            return true;
        }
        String shopId;
        try {
            shopId = UUID.fromString(args[1]).toString();
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cA shop ID is a UUID, the name of its file in Data/.");
            return true;
        }
        ShopTrades.Intent intent;
        try {
            Optional<ShopTrades.Intent> found = trades.inspect(shopId);
            if (found.isEmpty()) {
                sender.sendMessage("§aShop " + shopId + " has no unfinished trade.");
                return true;
            }
            intent = found.get();
        } catch (IOException ex) {
            intent = new ShopTrades.Intent(null, null, UNREADABLE);
            sender.sendMessage("§cThe trade record of shop " + shopId + " cannot be read: " + ex.getMessage());
        }
        boolean ledgerReady = Denars.ready();
        Optional<Result> posting = ledgerReady && intent.operationId() != null
                ? Denars.status(intent.operationId()) : Optional.empty();
        String postingText = !ledgerReady ? "unknown (ledger not ready)"
                : intent.operationId() == null ? "unknown" : posting.map(Result::name).orElse("absent");
        String summary = "shop " + shopId + " state " + intent.state() + ", posting " + postingText
                + (intent.operationId() == null ? "" : ", operation " + intent.operationId())
                + (intent.request() == null ? "" : ", " + describe(intent.request()));
        Clear verdict = clearable(intent.state(), posting, ledgerReady);
        if (!clear) {
            show(sender, shopId, intent, postingText, verdict);
            ShopAudit.record(auditFile, logger, sender.getName(), "viewed the trade at " + summary);
            return true;
        }
        if (verdict == Clear.LEDGER) {
            sender.sendMessage("§cThe ledger is not ready, so nothing was cleared. Try again once DenarEconomy is up.");
            return true;
        }
        if (verdict == Clear.CONFIRM && !confirm) {
            show(sender, shopId, intent, postingText, verdict);
            return true;
        }
        try {
            trades.clear(shopId);
        } catch (IOException ex) {
            sender.sendMessage("§cCould not remove the trade record: " + ex.getMessage());
            return true;
        }
        ShopAudit.record(auditFile, logger, sender.getName(),
                "cleared the trade at " + summary + (confirm ? " (confirmed)" : ""));
        sender.sendMessage("§aCleared the trade at shop " + shopId + "; it trades again.");
        sender.sendMessage("§7If its owner is dead, run /estate retry <deathId> bartershops.");
        return true;
    }

    private static void show(CommandSender sender, String shopId, ShopTrades.Intent intent, String posting, Clear verdict) {
        sender.sendMessage("§eShop " + shopId + ": unfinished trade, state " + intent.state() + ", posting " + posting);
        if (intent.operationId() != null) sender.sendMessage("§7Operation " + intent.operationId());
        if (intent.request() != null) sender.sendMessage("§7" + describe(intent.request()));
        String command = "/bartershops intent " + shopId + " clear";
        sender.sendMessage(switch (verdict) {
            case YES -> "§aNo money moved. " + command + " removes it.";
            case LEDGER -> "§cThe ledger is not ready; nothing can be cleared yet.";
            case CONFIRM -> "prepared".equals(intent.state())
                    ? "§cThe payment applied but the items did not move. Hand over the items, then " + command + " --confirm."
                    : "§cThe items may already have moved. Check the chest and the customer, put things right, then "
                            + command + " --confirm.";
        });
    }

    private static String describe(ShopTrades.Request request) {
        return request.direction() + " of " + request.quantity() + " for " + request.cents() + " cents, customer "
                + (request.customer() == null ? "?" : request.customer().characterId()) + ", owner "
                + (request.owner() == null ? "?" : request.owner().characterId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ShopEvents.ADMIN_PERMISSION)) return List.of();
        if (args.length == 1) return List.of("intent");
        if (args.length == 3) return List.of("clear");
        if (args.length == 4 && args[2].equalsIgnoreCase("clear")) return List.of("--confirm");
        return List.of();
    }
}
