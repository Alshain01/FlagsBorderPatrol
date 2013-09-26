/* Copyright 2013 Kevin Seiden. All rights reserved.

 This works is licensed under the Creative Commons Attribution-NonCommercial 3.0

 You are Free to:
    to Share — to copy, distribute and transmit the work
    to Remix — to adapt the work

 Under the following conditions:
    Attribution — You must attribute the work in the manner specified by the author (but not in any way that suggests that they endorse you or your use of the work).
    Non-commercial — You may not use this work for commercial purposes.

 With the understanding that:
    Waiver — Any of the above conditions can be waived if you get permission from the copyright holder.
    Public Domain — Where the work or any of its elements is in the public domain under applicable law, that status is in no way affected by the license.
    Other Rights — In no way are any of the following rights affected by the license:
        Your fair dealing or fair use rights, or other applicable copyright exceptions and limitations;
        The author's moral rights;
        Rights other persons may have either in the work itself or in how the work is used, such as publicity or privacy rights.

 Notice — For any reuse or distribution, you must make clear to others the license terms of this work. The best way to do this is with a link to this web page.
 http://creativecommons.org/licenses/by-nc/3.0/
*/

package alshain01.FlagsBorderPatrol;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import alshain01.Flags.Flags;
import alshain01.Flags.ModuleYML;
import alshain01.Flags.Flag;
import alshain01.Flags.Registrar;
import alshain01.Flags.area.Area;
import alshain01.Flags.events.PlayerChangedAreaEvent;

/**
 * Flags - Border Patrol
 * Module that adds border crossing flags to the plug-in Flags.
 * 
 * @author Alshain01
 */
public class FlagsBorderPatrol extends JavaPlugin {
	// Contains a list of players who have recently been sent an AllowEntry/AllowLeave message
	private Set<String> playersMessaged = new HashSet<String>();
	private BukkitTask playerCleanupTask;
	private PlayerCleanupTask playerMessageCleanupRunnable;
	private boolean canTrackPlayer;
	
	/**
	 * Called when this module is enabled
	 */
	@Override
	public void onEnable(){
		// Connect to the data file
		ModuleYML dataFile = new ModuleYML(this, "flags.yml");
		
		// Register with Flags
		Registrar flags = Flags.instance.getRegistrar();
		for(String f : dataFile.getModuleData().getConfigurationSection("Flag").getKeys(false)) {
			ConfigurationSection data = dataFile.getModuleData().getConfigurationSection("Flag." + f);

			
			// The description that appears when using help commands.
			String desc = data.getString("Description");
			
			// The default value.
			boolean def = data.getBoolean("Default");
			
			// The default message players get while in the area.
			String area = data.getString("AreaMessage");
			
			// The default message players get while in an world.
			String world = data.getString("WorldMessage");
			
			// Register it!
			// Be sure to send a plug-in name or group description for the help command!
			// It can be this.getName() or another string.
			flags.register(f, desc, def, "BorderCrossing", area, world);
		}			
		
		// Slows down message spam.
		canTrackPlayer = Flags.instance.checkAPI("1.3.2");
		if(canTrackPlayer) {
			playerMessageCleanupRunnable = new PlayerCleanupTask();
			playerCleanupTask = playerMessageCleanupRunnable.runTaskTimerAsynchronously(this, 0, 100);
		}

		// Load plug-in events and data
		Bukkit.getServer().getPluginManager().registerEvents(new AreaListener(), this);
	}
	
	@Override
	public void onDisable(){
		if(canTrackPlayer) {
			playerCleanupTask.cancel();
		}
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
		/*
		 * Event handler for AllowEntry and AllowLeave
		 */
		@EventHandler (ignoreCancelled = true)
		private void onPlayerChangeArea(PlayerChangedAreaEvent e) {
			Registrar flags = Flags.instance.getRegistrar();
			
			if(!canCrossBorder(e.getArea(), e.getPlayer(), flags.getFlag("AllowEntry"), true) || 
					!canCrossBorder(e.getAreaLeft(), e.getPlayer(),  flags.getFlag("AllowLeave"), true)) {
				e.setCancelled(true);
			}
		}
		
		private boolean canCrossBorder(Area area, Player player, Flag flag, boolean notify) {
			if (flag == null) { return true; }
			
			// Player can enter because of flag setting, permission, or trust.
			if(area.getValue(flag, false) || flag.hasBypassPermission(player) 
					|| area.getTrustList(flag).contains(player.getName())) { return true; }
			
			// Player is not allowed to enter or leave this area.
			if (notify && !playersMessaged.contains(player.getName())) {
				// Player was not told that recently
				if(canTrackPlayer) { playersMessaged.add(player.getName());	}
				player.sendMessage(area.getMessage(flag)
						.replaceAll("<0>", area.getAreaType().toLowerCase()));
			}
			return false;
		}
		
		/*
		 * Event Handler for NotifyEnter and NotifyExit
		 */
		@EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
		private void onPlayerChangeAreaMonitor(PlayerChangedAreaEvent e) {
			Area areaTo = e.getArea();
			Area areaFrom = e.getAreaLeft();
			Player player = e.getPlayer();
			Registrar flags = Flags.instance.getRegistrar();

			// Don't welcome them to the area and then forcibly remove them.
			if (canCrossBorder(areaTo, e.getPlayer(), flags.getFlag("AllowEntry"), false) 
					&& canCrossBorder(areaFrom, e.getPlayer(), flags.getFlag("AllowLeave"), false)) {
				
				// Player has not been forcibly returned.
				// Check to see if we should notify them.
				// Don't bother the area owner.
				Flag ne = flags.getFlag("NotifyEnter");
				Flag nx = flags.getFlag("NotifyExit");
				if (ne != null && areaTo.getValue(ne, false) 
						&& !areaTo.getOwners().contains(player.getName())) {
					// Send the message
					e.getPlayer().sendMessage(areaTo.getMessage(ne)
							.replaceAll("<2>", player.getDisplayName()));
				} else if (nx != null && areaFrom.getValue(nx, false)
						&& !areaFrom.getOwners().contains(player.getName())) { // Only send one notification at any time.
					// Send the message
					e.getPlayer().sendMessage(areaFrom.getMessage(nx)
							.replaceAll("<2>", player.getDisplayName()));
				}
			}
		}
		
		/*
		 * Event Handler for Flight
		 */
		@EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
		private void onPlayerChangedArea(PlayerChangedAreaEvent e) {
			// Look at that, the flag I could never get working... works!
			// ... and it was so simple all along.
			if(Bukkit.getServer().getAllowFlight()) { return; }
			
			Player player = e.getPlayer();
			if(player.getGameMode() == GameMode.CREATIVE) { return; }
			
			Flag flag = Flags.instance.getRegistrar().getFlag("Flight");
			if(flag == null) { return; }
			
			if(e.getArea().getValue(flag, false)) {
				// Player entered a flight allowed area
				if(!player.getAllowFlight()) {
					player.sendMessage(e.getArea().getMessage(flag));
					player.setAllowFlight(true);
				}
			} else {
				// Player can fly because of permission or trust.
				if(flag.hasBypassPermission(player) ||
				e.getArea().getTrustList(flag).contains(player.getName())) { return; }
				
				// Player entered a flight disabled area
				// We need to take them out of the sky gently.
				// (of course if we didn't, it sure would be fun to watch)
				if(player.isFlying()) {
					// Teleport the player to the ground so they don't die.
					// Have fun deciphering this next line.
					player.teleport(player.getWorld().getHighestBlockAt(player.getLocation()).getLocation().add(0, 1, 0), TeleportCause.PLUGIN);
					player.setFlying(false);
				}
				player.setAllowFlight(false);
			}
		}
	}
}
