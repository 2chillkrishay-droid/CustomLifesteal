package com.custom.lifesteal;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private NamespacedKey heartKey;
    private NamespacedKey reviveKey;
    private final double MAX_HEALTH = 40.0; // 20 hearts
    private final Set<UUID> awaitingReviveInput = new HashSet<>();
    private final Set<UUID> pendingResetPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        heartKey = new NamespacedKey(this, "custom_heart");
        reviveKey = new NamespacedKey(this, "revive_beacon");
        getServer().getPluginManager().registerEvents(this, this);
        registerRecipes();
    }

    // --- CUSTOM ITEMS ---

    public ItemStack createHeartItem(int amount) {
        ItemStack heart = new ItemStack(Material.NETHER_STAR, amount);
        ItemMeta meta = heart.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Heart");
            meta.setLore(List.of(ChatColor.GRAY + "Right-click to gain +1 permanent heart!"));
            meta.getPersistentDataContainer().set(heartKey, PersistentDataType.BYTE, (byte) 1);
            heart.setItemMeta(meta);
        }
        return heart;
    }

    public ItemStack createReviveBeacon(int amount) {
        ItemStack beacon = new ItemStack(Material.BEACON, amount);
        ItemMeta meta = beacon.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Revive Beacon");
            meta.setLore(List.of(ChatColor.GRAY + "Right-click to revive an eliminated player!"));
            meta.getPersistentDataContainer().set(reviveKey, PersistentDataType.BYTE, (byte) 1);
            beacon.setItemMeta(meta);
        }
        return beacon;
    }

    // --- CRAFTING RECIPES ---

    private void registerRecipes() {
        // 1. Custom Heart Recipe
        ShapedRecipe heartRecipe = new ShapedRecipe(new NamespacedKey(this, "heart_recipe"), createHeartItem(1));
        heartRecipe.shape("HDH", "DND", "GDG");
        heartRecipe.setIngredient('H', Material.PLAYER_HEAD);
        heartRecipe.setIngredient('D', Material.DIAMOND_BLOCK);
        heartRecipe.setIngredient('N', Material.NETHERITE_INGOT);
        heartRecipe.setIngredient('G', Material.GOLD_BLOCK);
        Bukkit.addRecipe(heartRecipe);

        // 2. Revive Beacon Recipe
        ShapedRecipe reviveRecipe = new ShapedRecipe(new NamespacedKey(this, "revive_beacon_recipe"), createReviveBeacon(1));
        reviveRecipe.shape("HNH", "NSN", "HNH");
        reviveRecipe.setIngredient('H', Material.PLAYER_HEAD);
        reviveRecipe.setIngredient('N', Material.NETHERITE_INGOT);
        reviveRecipe.setIngredient('S', Material.NETHER_STAR);
        Bukkit.addRecipe(reviveRecipe);

        // 3. Cheaper Golden Apple Recipe (No corners)
        ShapedRecipe gappleRecipe = new ShapedRecipe(new NamespacedKey(this, "cheap_gapple_recipe"), new ItemStack(Material.GOLDEN_APPLE));
        gappleRecipe.shape(" G ", "GAG", " G ");
        gappleRecipe.setIngredient('G', Material.GOLD_INGOT);
        gappleRecipe.setIngredient('A', Material.APPLE);
        Bukkit.addRecipe(gappleRecipe);
    }

    // --- LIFESTEAL MECHANICS ---

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.equals(victim)) {
            AttributeInstance victimAttr = victim.getAttribute(Attribute.MAX_HEALTH);
            AttributeInstance killerAttr = killer.getAttribute(Attribute.MAX_HEALTH);

            if (victimAttr != null && killerAttr != null) {
                double newVictimHealth = victimAttr.getBaseValue() - 2.0;

                if (newVictimHealth <= 0) {
                    victimAttr.setBaseValue(20.0); // Reset to 10 hearts upon revival
                    Bukkit.getBanList(BanList.Type.NAME).addBan(
                            victim.getName(),
                            ChatColor.RED + "You ran out of hearts!",
                            null,
                            null
                    );
                    victim.kickPlayer(ChatColor.RED + "You ran out of hearts!");
                } else {
                    victimAttr.setBaseValue(newVictimHealth);
                    victim.sendMessage(ChatColor.RED + "You lost 1 heart to " + killer.getName() + "!");
                }

                if (killerAttr.getBaseValue() < MAX_HEALTH) {
                    killerAttr.setBaseValue(Math.min(MAX_HEALTH, killerAttr.getBaseValue() + 2.0));
                    killer.sendMessage(ChatColor.GREEN + "You stole 1 heart from " + victim.getName() + "!");
                } else {
                    killer.sendMessage(ChatColor.YELLOW + "You are already at the 20 heart limit!");
                }
            }
        }
    }

    // --- ITEM INTERACTIONS (HEART & REVIVE BEACON) ---

    @EventHandler
    public void onItemUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        Player player = event.getPlayer();

        // Using a Heart
        if (meta.getPersistentDataContainer().has(heartKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);

            if (attr != null) {
                if (attr.getBaseValue() >= MAX_HEALTH) {
                    player.sendMessage(ChatColor.RED + "You are already at the maximum of 20 hearts!");
                    return;
                }

                item.setAmount(item.getAmount() - 1);
                attr.setBaseValue(Math.min(MAX_HEALTH, attr.getBaseValue() + 2.0));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                player.sendMessage(ChatColor.GREEN + "You consumed a Heart! (+1 Max Heart)");
            }
        }
        // Using a Revive Beacon
        else if (meta.getPersistentDataContainer().has(reviveKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            awaitingReviveInput.add(player.getUniqueId());
            player.sendMessage(ChatColor.AQUA + "Type the exact username of the banned player in chat to revive them (or type 'cancel'):");
        }
    }

    // Handles chat input for Revive Beacon
    @EventHandler
    public void onChatRevive(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingReviveInput.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        awaitingReviveInput.remove(player.getUniqueId());
        String targetName = event.getMessage().trim();

        if (targetName.equalsIgnoreCase("cancel")) {
            player.sendMessage(ChatColor.YELLOW + "Revive cancelled.");
            return;
        }

        // Switch back to main server thread to safely modify inventory and ban list
        Bukkit.getScheduler().runTask(this, () -> {
            BanList<String> banList = Bukkit.getBanList(BanList.Type.NAME);
            if (!banList.isBanned(targetName)) {
                player.sendMessage(ChatColor.RED + targetName + " is not currently banned!");
                return;
            }

            // Check if player still holds the Revive Beacon
            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem.hasItemMeta() && handItem.getItemMeta().getPersistentDataContainer().has(reviveKey, PersistentDataType.BYTE)) {
                handItem.setAmount(handItem.getAmount() - 1);
                banList.pardon(targetName);
                Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + targetName + ChatColor.GREEN + " has been revived by " + player.getName() + "!");
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else {
                player.sendMessage(ChatColor.RED + "You must hold the Revive Beacon in your hand!");
            }
        });
    }

    // Resets offline players' hearts when they log back in after /resethearts
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (pendingResetPlayers.remove(player.getUniqueId())) {
            AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(20.0);
                player.setHealth(20.0);
                player.sendMessage(ChatColor.YELLOW + "An admin reset all hearts! You are back at 10 hearts.");
            }
        }
    }

    // --- COMMANDS (/withdraw & /resethearts) ---

    @Override
