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
package io.github.alshain01.FlagsBorderPatrol;

import io.github.alshain01.Flags.Flag;
import io.github.alshain01.Flags.Flags;
import io.github.alshain01.Flags.ModuleYML;
import io.github.alshain01.Flags.Registrar;
import io.github.alshain01.Flags.area.Area;
import io.github.alshain01.Flags.events.PlayerChangedAreaEvent;

import java.util.HashSet;
import java.util.Set;

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
 * Flags - Border Patrol 
 * Module that adds border crossing flags to the plug-in Flags.
 * 
 * @author Alshain01
 */
public class FlagsBorderPatrol extends JavaPlugin {
	// Contains a list of players who have recently been sent an
	// AllowEntry/AllowLeave message
	private final Set<String> playersMessaged = new HashSet<String>();

	private boolean canTrackPlayer;
	
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
		Flags.getRegistrar().register(new ModuleYML(this, "flags.yml"), "BorderPatrol");

		// Slows down message spam.
		canTrackPlayer = Flags.checkAPI("1.3.2");
		if (canTrackPlayer) {
			new PlayerCleanupTask().runTaskTimerAsynchronously(this, 0, 100);
		}

		// Load plug-in events and data
		Bukkit.getServer().getPluginManager().registerEvents(new AreaListener(), this);
	}
	
	private class PlayerCleanupTask extends BukkitRunnable {
		@Override
		public void run() {
			playersMessaged.clear();
		}
	}

	/*
	 * The event handlers for the flags we created earlier
	 */
	private class AreaListener implements Listener {
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
			if (notify && !playersMessaged.contains(player.getName())) {
				// Player was not told that recently
				if (canTrackPlayer) {
					playersMessaged.add(player.getName());
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
			final Registrar flags = Flags.getRegistrar();

			if (!canCrossBorder(e.getArea(), e.getPlayer(),	flags.getFlag("AllowEntry"), true)
					|| !canCrossBorder(e.getAreaLeft(), e.getPlayer(), flags.getFlag("AllowLeave"), true)) {
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
			final Registrar flags = Flags.getRegistrar();

			// Don't welcome them to the area and then forcibly remove them.
			if (canCrossBorder(areaTo, e.getPlayer(), flags.getFlag("AllowEntry"), false)
					&& canCrossBorder(areaFrom, e.getPlayer(), flags.getFlag("AllowLeave"), false)) {

				// Player has not been forcibly returned.
				// Check to see if we should notify them.
				// Don't bother the area owner.
				final Flag ne = flags.getFlag("NotifyEnter");
				final Flag nx = flags.getFlag("NotifyExit");
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

			final Flag flag = Flags.getRegistrar().getFlag("Flight");
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
