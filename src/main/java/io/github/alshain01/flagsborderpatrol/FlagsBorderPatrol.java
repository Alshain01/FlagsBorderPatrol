/* Copyright 2013 Kevin Seiden. All rights reserved.

 This works is licensed under the Creative Commons Attribution-NonCommercial 3.0

 You are Free to:
    to Share: to copy, distribute and transmit the work
    to Remix: to adapt the work

 Under the following conditions:
    Attribution: You must attribute the work in the manner specified by the author (but not in any way that suggests that they endorse you or your use of the work).
    Non-commercial: You may not use this work for commercial purposes.

 With the understanding that:
    Waiver: Any of the above conditions can be waived if you get permission from the copyright holder.
    Public Domain: Where the work or any of its elements is in the public domain under applicable law, that status is in no way affected by the license.
    Other Rights: In no way are any of the following rights affected by the license:
        Your fair dealing or fair use rights, or other applicable copyright exceptions and limitations;
        The author's moral rights;
        Rights other persons may have either in the work itself or in how the work is used, such as publicity or privacy rights.

 Notice: For any reuse or distribution, you must make clear to others the license terms of this work. The best way to do this is with a link to this web page.
 http://creativecommons.org/licenses/by-nc/3.0/
 */
package io.github.alshain01.flagsborderpatrol;

import io.github.alshain01.flags.Flag;
import io.github.alshain01.flags.Flags;
import io.github.alshain01.flags.ModuleYML;
import io.github.alshain01.flags.Registrar;
import io.github.alshain01.flags.area.Area;
import io.github.alshain01.flags.System;
import io.github.alshain01.flags.events.PlayerChangedAreaEvent;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Flags Border Patrol - Module that adds border crossing flags to the plug-in Flags.
 */
public class FlagsBorderPatrol extends JavaPlugin {
	/**
	 * Called when this module is enabled
	 */
	@Override
	public void onEnable() {
		final PluginManager pm = Bukkit.getServer().getPluginManager();

		if (!pm.isPluginEnabled("Flags")) {
			getLogger().severe("Flags was not found. Shutting down.");
			pm.disablePlugin(this);
		}

		if (!Flags.getBorderPatrolEnabled()) {
			getLogger().severe("Flags Border Patrol is disabled. Shutting down.");
			pm.disablePlugin(this);
		}

		// Connect to the data file and register the flags
		Set<Flag> flags = Flags.getRegistrar().register(new ModuleYML(this, "flags.yml"), "BorderPatrol");
        Map<String, Flag> flagMap = new HashMap<String, Flag>();
        for(Flag f : flags) {
            flagMap.put(f.getName(), f);
        }

		// Load plug-in events and data
		Bukkit.getServer().getPluginManager().registerEvents(new AreaListener(this, flagMap), this);
	}
	
	/*
	 * The event handlers for the flags we created earlier
	 */
	private class AreaListener implements Listener {
        // Contains a list of players who have recently been sent an
        // AllowEntry/AllowLeave message in order to prevent spamming
        private final JavaPlugin plugin;
        private final Map<String, Flag> flags;
        private final Set<String> playersMessaged = new HashSet<String>();
        private final boolean canTrackPlayer = Flags.checkAPI("1.3.2");

        AreaListener(JavaPlugin plugin, Map<String, Flag> flags) {
            this.plugin = plugin;
            this.flags = flags;
        }

		private boolean canCrossBorder(Area area, Player player, Flag flag,	boolean notify) {
			if (flag == null) {
				return true;
			}

			// Player can enter because of flag setting, permission, or trust.
			if (area.getValue(flag, false)
					|| player.hasPermission(flag.getBypassPermission())
					|| area.hasTrust(flag, player)) {
				return true;
			}

			// Player is not allowed to enter or leave this area.
            final String pName = player.getName();
			if (notify && !playersMessaged.contains(pName)) {
				// Player was not told that recently
				if (canTrackPlayer) {
					playersMessaged.add(pName);
                    new BukkitRunnable() {
                        public void run() {
                            if(playersMessaged.contains(pName)) {
                                playersMessaged.remove(pName);
                            }
                        }
                    }.runTaskLater(plugin, 100);
				}
				player.sendMessage(area.getMessage(flag, player.getName()));
			}
			return false;
		}

		/*
		 * Event handler for AllowEntry and AllowLeave
		 */
		@EventHandler(ignoreCancelled = true)
		private void onPlayerChangeArea(PlayerChangedAreaEvent e) {
			if (!canCrossBorder(e.getArea(), e.getPlayer(),	flags.get("AllowEntry"), true)
					|| !canCrossBorder(e.getAreaLeft(), e.getPlayer(), flags.get("AllowLeave"), true)) {
				e.setCancelled(true);
			}
		}

		/*
		 * Event Handler for NotifyEnter and NotifyExit
		 */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		private void onPlayerChangeAreaMonitor(PlayerChangedAreaEvent e) {
			final Area areaTo = e.getArea();
			final Area areaFrom = e.getAreaLeft();
			final Player player = e.getPlayer();

			// Don't welcome them to the area and then forcibly remove them.
			if (canCrossBorder(areaTo, e.getPlayer(), flags.get("AllowEntry"), false)
					&& canCrossBorder(areaFrom, e.getPlayer(), flags.get("AllowLeave"), false)) {

				// Player has not been forcibly returned.
				// Check to see if we should notify them.
				// Don't bother the area owner.
				final Flag ne = flags.get("NotifyEnter");
				final Flag nx = flags.get("NotifyExit");
				if (ne != null && areaTo.getValue(ne, false)
						&& !areaTo.getOwners().contains(player.getName())) {
					// Send the message
					e.getPlayer().sendMessage(areaTo.getMessage(ne, player.getName()));
				} else if (nx != null && areaFrom.getValue(nx, false)
						// Only send one notification at any time.
						&& !areaFrom.getOwners().contains(player.getName())) { 
					// Send the message
					e.getPlayer().sendMessage(areaFrom.getMessage(nx, player.getName()));
				}
			}
		}

		/*
		 * Event Handler for Flight
		 */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		private void onPlayerChangedArea(PlayerChangedAreaEvent e) {
			if (Bukkit.getServer().getAllowFlight()) {
				return;
			}

			final Player player = e.getPlayer();
			if (player.getGameMode() == GameMode.CREATIVE) {
				return;
			}

			final Flag flag = flags.get("Flight");
			if (flag == null) {
				return;
			}

			if (e.getArea().getValue(flag, false)) {
				// Player entered a flight allowed area
				if (!player.getAllowFlight()) {
					player.sendMessage(e.getArea().getMessage(flag));
					player.setAllowFlight(true);
				}
			} else {
				// Player can fly because of permission or trust.
				if (player.hasPermission(flag.getBypassPermission())
						|| e.getArea().hasTrust(flag, player)) {
					return;
				}

				// Player entered a flight disabled area
				// We need to take them out of the sky gently.
				// (of course if we didn't, it sure would be fun to watch)
				if (player.isFlying()) {
					// Teleport the player to the ground so they don't die.
					// Have fun deciphering this next line.
					player.teleport(player.getWorld()
							.getHighestBlockAt(player.getLocation())
							.getLocation().add(0, 1, 0),
							TeleportCause.PLUGIN);
					player.setFlying(false);
				}
				player.setAllowFlight(false);
			}
		}
	}
}
