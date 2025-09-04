/*
 * Copyright (c) 2019-2025 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.platform.neoforge;

import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * NeoForge-specific fix for the Bedrock loading screen issue.
 * This class monitors for ClientboundFinishConfigurationPacket and ensures
 * Bedrock players properly transition from loading screen to game world.
 */
public class NeoForgeConfigurationFix {
    private static final ConcurrentHashMap<String, Long> pendingPlayers = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Monitor for configuration completion
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (GeyserImpl.getInstance() == null) {
                    return;
                }

                // Check all pending players
                pendingPlayers.entrySet().removeIf(entry -> {
                    String playerName = entry.getKey();
                    long startTime = entry.getValue();
                    
                    // Timeout after 10 seconds
                    if (System.currentTimeMillis() - startTime > 10000) {
                        GeyserImpl.getInstance().getLogger().debug("NeoForgeConfigurationFix: Timeout for player " + playerName);
                        return true;
                    }

                    // Find the session
                    for (GeyserSession session : GeyserImpl.getInstance().getSessionManager().getAllSessions()) {
                        if (session.getPlayerEntity() != null && 
                            session.getPlayerEntity().getUsername().equals(playerName)) {
                            
                            GeyserImpl.getInstance().getLogger().debug("NeoForgeConfigurationFix: Monitoring " + playerName + 
                                " - sentSpawn: " + session.isSentSpawnPacket() + ", spawned: " + session.isSpawned());
                            
                            // Check if player is stuck on loading screen
                            if (session.isSentSpawnPacket() && !session.isSpawned()) {
                                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Detected stuck player " + playerName + ", sending PLAYER_SPAWN");
                                sendPlayerSpawnStatus(session);
                                return true; // Remove from pending
                            }
                            
                            // Also check if it's been more than 3 seconds and still not spawned
                            if (System.currentTimeMillis() - startTime > 3000 && !session.isSpawned()) {
                                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " taking too long to spawn, forcing PLAYER_SPAWN");
                                sendPlayerSpawnStatus(session);
                                return true; // Remove from pending
                            }
                            
                            break;
                        }
                    }
                    
                    return false; // Keep checking
                });
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error("NeoForgeConfigurationFix: Error in monitor thread", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public static void onPlayerJoin(String playerName) {
        if (!playerName.startsWith(".")) { // Only track Bedrock players (Floodgate prefix)
            return;
        }
        pendingPlayers.put(playerName, System.currentTimeMillis());
        GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Tracking Bedrock player " + playerName);
        
        // Immediately try to send spawn status after a short delay to ensure session is ready
        scheduler.schedule(() -> {
            try {
                for (GeyserSession session : GeyserImpl.getInstance().getSessionManager().getAllSessions()) {
                    if (session.getPlayerEntity() != null && 
                        session.getPlayerEntity().getUsername().equals(playerName)) {
                        
                        GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Found session for " + playerName + 
                            ", sentSpawn: " + session.isSentSpawnPacket() + ", spawned: " + session.isSpawned());
                        
                        // Only send spawn status if the session is in the right state
                        // We want sentSpawn: true, spawned: false (stuck on loading screen)
                        if (session.isSentSpawnPacket() && !session.isSpawned()) {
                            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " is stuck on loading screen, sending PLAYER_SPAWN");
                            sendPlayerSpawnStatus(session);
                        } else if (!session.isSentSpawnPacket()) {
                            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Session for " + playerName + " hasn't sent spawn packet yet, will retry later");
                            // Don't remove from pending, let the monitor check again
                            return;
                        } else {
                            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " is already spawned properly");
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error("NeoForgeConfigurationFix: Error in immediate spawn check for " + playerName, e);
            }
        }, 2, TimeUnit.SECONDS);
    }

    public static void onConfigurationFinish(GeyserSession session) {
        if (session == null || session.getPlayerEntity() == null) {
            return;
        }

        String playerName = session.getPlayerEntity().getUsername();
        if (!playerName.startsWith(".")) { // Only handle Bedrock players
            return;
        }

        // Schedule a check in 500ms to ensure the player has transitioned
        scheduler.schedule(() -> {
            try {
                if (session.isSentSpawnPacket() && !session.isSpawned()) {
                    sendPlayerSpawnStatus(session);
                }
                pendingPlayers.remove(playerName);
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().error("NeoForgeConfigurationFix: Error checking player " + playerName, e);
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private static void sendPlayerSpawnStatus(GeyserSession session) {
        try {
            String playerName = session.getPlayerEntity().getUsername();
            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Attempting to send PLAYER_SPAWN status to " + playerName);
            
            PlayStatusPacket playStatusPacket = new PlayStatusPacket();
            playStatusPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
            session.sendUpstreamPacket(playStatusPacket);
            
            // Also try to set spawned state
            try {
                session.setSpawned(true);
                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Successfully sent PLAYER_SPAWN status to " + playerName);
            } catch (Exception spawnException) {
                GeyserImpl.getInstance().getLogger().error("NeoForgeConfigurationFix: Sent packet but couldn't set spawned state for " + playerName + ": " + spawnException.getMessage());
            }
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("NeoForgeConfigurationFix: Failed to send spawn status to " + 
                (session.getPlayerEntity() != null ? session.getPlayerEntity().getUsername() : "unknown"), e);
        }
    }

    public static void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
