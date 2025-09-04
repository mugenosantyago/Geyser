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
                            
                            // Check if player is stuck on loading screen (normal case)
                            if (session.isSentSpawnPacket() && !session.isSpawned()) {
                                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Detected stuck player " + playerName + ", sending PLAYER_SPAWN");
                                sendPlayerSpawnStatus(session);
                                return true; // Remove from pending
                            }
                            
                            // Check for unusual state: spawned but no spawn packet sent
                            if (!session.isSentSpawnPacket() && session.isSpawned()) {
                                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " in unusual state, forcing PLAYER_SPAWN");
                                sendPlayerSpawnStatus(session);
                                return true; // Remove from pending
                            }
                            
                            // Also check if it's been more than 3 seconds and still not properly spawned
                            if (System.currentTimeMillis() - startTime > 3000 && 
                                (!session.isSpawned() || !session.isSentSpawnPacket())) {
                                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " taking too long to spawn properly, forcing PLAYER_SPAWN");
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
                        
                        // Handle different session states
                        if (session.isSentSpawnPacket() && !session.isSpawned()) {
                            // Normal stuck state: spawn packet sent but client not spawned
                            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " is stuck on loading screen, sending PLAYER_SPAWN");
                            sendPlayerSpawnStatus(session);
                        } else if (!session.isSentSpawnPacket() && session.isSpawned()) {
                            // Unusual state: server thinks spawned but client never got spawn packet
                            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Player " + playerName + " in unusual state (spawned but no spawn packet sent), forcing PLAYER_SPAWN");
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
            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Attempting comprehensive spawn fix for " + playerName);
            
            // Send multiple packets to ensure the client exits loading screen
            
            // 1. Send PLAYER_SPAWN status
            PlayStatusPacket playStatusPacket = new PlayStatusPacket();
            playStatusPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
            session.sendUpstreamPacket(playStatusPacket);
            GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Sent PLAYER_SPAWN status to " + playerName);
            
            // 2. Send LOGIN_SUCCESS status as well (sometimes needed for stuck clients)
            try {
                PlayStatusPacket loginSuccessPacket = new PlayStatusPacket();
                loginSuccessPacket.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
                session.sendUpstreamPacket(loginSuccessPacket);
                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Sent LOGIN_SUCCESS status to " + playerName);
            } catch (Exception loginException) {
                GeyserImpl.getInstance().getLogger().debug("NeoForgeConfigurationFix: Could not send LOGIN_SUCCESS: " + loginException.getMessage());
            }
            
            // 3. Try to trigger the session's own spawn logic
            try {
                // Force the session to mark itself as spawned
                session.setSpawned(true);
                
                // Also try to call the session's spawn method if it exists
                try {
                    java.lang.reflect.Method spawnMethod = session.getClass().getDeclaredMethod("spawn");
                    spawnMethod.setAccessible(true);
                    spawnMethod.invoke(session);
                    GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Called session spawn method for " + playerName);
                } catch (NoSuchMethodException noMethod) {
                    // Method doesn't exist, that's fine
                } catch (Exception spawnMethodException) {
                    GeyserImpl.getInstance().getLogger().debug("NeoForgeConfigurationFix: Could not call spawn method: " + spawnMethodException.getMessage());
                }
                
                GeyserImpl.getInstance().getLogger().info("NeoForgeConfigurationFix: Comprehensive spawn fix completed for " + playerName);
            } catch (Exception spawnException) {
                GeyserImpl.getInstance().getLogger().error("NeoForgeConfigurationFix: Could not set spawned state for " + playerName + ": " + spawnException.getMessage());
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
