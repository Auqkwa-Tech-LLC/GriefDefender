/*

 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
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
 */
package com.griefdefender.permission;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.griefdefender.GDPlayerData;
import com.griefdefender.GriefDefenderPlugin;
import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.Subject;
import com.griefdefender.api.Tristate;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.claim.ClaimContexts;
import com.griefdefender.api.claim.ClaimType;
import com.griefdefender.api.claim.TrustType;
import com.griefdefender.api.permission.Context;
import com.griefdefender.api.permission.ContextKeys;
import com.griefdefender.api.permission.PermissionManager;
import com.griefdefender.api.permission.PermissionResult;
import com.griefdefender.api.permission.ResultTypes;
import com.griefdefender.api.permission.flag.Flag;
import com.griefdefender.api.permission.flag.FlagData;
import com.griefdefender.api.permission.flag.FlagDefinition;
import com.griefdefender.api.permission.flag.Flags;
import com.griefdefender.api.permission.option.Option;
import com.griefdefender.api.permission.option.Options;
import com.griefdefender.api.permission.option.type.CreateModeType;
import com.griefdefender.api.permission.option.type.CreateModeTypes;
import com.griefdefender.api.permission.option.type.GameModeType;
import com.griefdefender.api.permission.option.type.GameModeTypes;
import com.griefdefender.api.permission.option.type.WeatherType;
import com.griefdefender.api.permission.option.type.WeatherTypes;
import com.griefdefender.cache.EventResultCache;
import com.griefdefender.cache.MessageCache;
import com.griefdefender.cache.PermissionHolderCache;
import com.griefdefender.claim.GDClaim;
import com.griefdefender.claim.GDClaimManager;
import com.griefdefender.command.CommandHelper;
import com.griefdefender.configuration.MessageStorage;
import com.griefdefender.configuration.category.BanCategory;
import com.griefdefender.event.GDCauseStackManager;
import com.griefdefender.event.GDFlagPermissionEvent;
import com.griefdefender.internal.registry.BlockTypeRegistryModule;
import com.griefdefender.internal.registry.EntityTypeRegistryModule;
import com.griefdefender.internal.registry.GDEntityType;
import com.griefdefender.internal.registry.ItemTypeRegistryModule;
import com.griefdefender.internal.tracking.chunk.GDChunk;
import com.griefdefender.internal.util.NMSUtil;
import com.griefdefender.permission.option.GDOptions;
import com.griefdefender.provider.PermissionProvider.PermissionDataType;
import com.griefdefender.registry.FlagRegistryModule;
import com.griefdefender.util.EconomyUtil;
import com.griefdefender.util.PermissionUtil;
import com.griefdefender.util.PlayerUtil;

import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.adapter.bukkit.TextAdapter;
import net.kyori.text.format.TextColor;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GDPermissionManager implements PermissionManager {

    private static final Pattern BLOCKSTATE_PATTERN = Pattern.compile("(?:\\w+=\\w+,)*\\w+=\\w+", Pattern.MULTILINE);

    private static GDPermissionManager instance;
    public boolean blacklistCheck = false;
    private Event currentEvent;
    private Location eventLocation;
    private GDPermissionHolder eventSubject;
    private GDPlayerData eventPlayerData;
    private String eventSourceId = "none";
    private String eventTargetId = "none";
    private Set<Context> eventContexts = new HashSet<>();
    private Component eventMessage;
    private static final Pattern PATTERN_META = Pattern.compile("\\.[\\d+]*$");

    private enum BanType {
        BLOCK,
        ENTITY,
        ITEM
    }

    public GDPermissionHolder getDefaultHolder() {
        return GriefDefenderPlugin.DEFAULT_HOLDER;
    }

    @Override
    public Tristate getActiveFlagPermissionValue(Claim claim, Subject subject, Flag flag, Object source, Object target, Set<Context> contexts, TrustType type, boolean checkOverride) {
        return getFinalPermission(null, null, contexts, claim, flag, source, target, (GDPermissionHolder) subject, null, checkOverride);
    }

    public Tristate getFinalPermission(Event event, Location location, Claim claim, Flag flag, Object source, Object target, GDPermissionHolder permissionHolder) {
        return getFinalPermission(event, location, claim, flag, source, target, permissionHolder, null, false);
    }

    public Tristate getFinalPermission(Event event, Location location, Claim claim, Flag flag, Object source, Object target, Player player) {
        final GDPermissionHolder permissionHolder = PermissionHolderCache.getInstance().getOrCreateUser(player);
        return getFinalPermission(event, location, claim, flag, source, target, permissionHolder, null, false);
    }

    public Tristate getFinalPermission(Event event, Location location, Claim claim, Flag flag, Object source, Object target, Player player, boolean checkOverride) {
        final GDPermissionHolder permissionHolder = PermissionHolderCache.getInstance().getOrCreateUser(player);
        return getFinalPermission(event, location, claim, flag, source, target, permissionHolder, null, checkOverride);
    }

    public Tristate getFinalPermission(Event event, Location location, Claim claim, Flag flag, Object source, Object target, GDPermissionHolder permissionHolder, boolean checkOverride) {
        return getFinalPermission(event, location, claim, flag, source, target, permissionHolder, null, checkOverride);
    }

    public Tristate getFinalPermission(Event event, Location location, Claim claim, Flag flag, Object source, Object target, Player player, TrustType type, boolean checkOverride) {
        final GDPermissionHolder permissionHolder = PermissionHolderCache.getInstance().getOrCreateUser(player);
        return getFinalPermission(event, location, claim, flag, source, target, permissionHolder, type, checkOverride);
    }

    public Tristate getFinalPermission(Event event, Location location, Claim claim, Flag flag, Object source, Object target, GDPermissionHolder permissionHolder, TrustType type, boolean checkOverride) {
        return getFinalPermission(event, location, new HashSet<>(), claim, flag, source, target, permissionHolder, type, checkOverride);
    }

    public Tristate getFinalPermission(Event event, Location location, Set<Context> contexts, Claim claim, Flag flag, Object source, Object target, GDPermissionHolder permissionHolder, TrustType type, boolean checkOverride) {
        if (claim == null) {
            return Tristate.TRUE;
        }

        GDPlayerData playerData = null;
        final GDPermissionUser user = permissionHolder instanceof GDPermissionUser ? (GDPermissionUser) permissionHolder : null;
        this.eventSubject = user;
        this.eventMessage = null;
        this.eventSourceId = "none";
        this.eventTargetId = "none";
        if (permissionHolder != null) {
            if (user != null) {
                playerData = GriefDefenderPlugin.getInstance().dataStore.getOrCreatePlayerData(claim.getWorldUniqueId(), user.getUniqueId());
            }
        }

        this.currentEvent = event;
        this.eventLocation = location;
        // refresh contexts
        this.eventContexts = new HashSet<>();

        if (source instanceof Player && flag != Flags.COLLIDE_BLOCK && flag != Flags.COLLIDE_ENTITY) {
            this.addPlayerContexts((Player) source, contexts, flag);
        }

        final Set<Context> sourceContexts = this.getPermissionContexts((GDClaim) claim, source, true);
        if (sourceContexts == null) {
            return Tristate.FALSE;
        }

        final Set<Context> targetContexts = this.getPermissionContexts((GDClaim) claim, target, false);
        if (targetContexts == null) {
            return Tristate.FALSE;
        }
        contexts.addAll(sourceContexts);
        contexts.addAll(targetContexts);
        contexts.add(((GDClaim) claim).getWorldContext());
        this.eventContexts = contexts;
        this.eventPlayerData = playerData;
        final String targetPermission = flag.getPermission();

        if (flag == Flags.ENTITY_SPAWN && GDOptions.isOptionEnabled(Options.SPAWN_LIMIT) && target instanceof LivingEntity) {
            // Check spawn limit
            final GDClaim gdClaim = (GDClaim) claim;
            final int spawnLimit = gdClaim.getSpawnLimit(contexts);
            if (spawnLimit > -1) {
                final Entity entity = (Entity) target;
                final int currentEntityCount = gdClaim.countEntities(entity);
                if (currentEntityCount >= spawnLimit) {
                    if (user != null && user.getOnlinePlayer() != null && (source == SpawnReason.ENDER_PEARL || source == SpawnReason.SPAWNER_EGG || source == SpawnReason.SPAWNER)) {
                        final String name = entity.getType().getName() == null ? entity.getType().name().toLowerCase() : entity.getType().getName();
                        final GDEntityType entityType = EntityTypeRegistryModule.getInstance().getById(name).orElse(null);
                        final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.OPTION_APPLY_SPAWN_LIMIT,
                                ImmutableMap.of(
                                "type", entityType.getId(),
                                "limit", spawnLimit));
                        GriefDefenderPlugin.sendMessage(user.getOnlinePlayer(), message);
                    }
                    return this.processResult(claim, flag.getPermission(), "spawn-limit", Tristate.FALSE, this.eventSubject);
                }
            }
        }

        if (user != null && playerData != null && !playerData.debugClaimPermissions && playerData.canIgnoreClaim(claim)) {
            return processResult(claim, targetPermission, "ignore", Tristate.TRUE, user);
        }
        if (checkOverride) {
            // First check for claim flag overrides
            final Tristate override = getFlagOverride(claim, permissionHolder == null ? GriefDefenderPlugin.DEFAULT_HOLDER : permissionHolder, playerData, targetPermission);
            if (override != Tristate.UNDEFINED) {
                return processResult(claim, targetPermission, type == null ? "none" : type.getName().toLowerCase(), override, user);
            }
        }

        if (playerData != null && user != null) {
            if (playerData.debugClaimPermissions) {
                if (type != null && claim.isUserTrusted(user.getUniqueId(), type)) {
                    return processResult(claim, targetPermission, type.getName().toLowerCase(), Tristate.TRUE, user);
                }
                return getClaimFlagPermission(claim, targetPermission);
            }
             // Check for ignoreclaims after override and debug checks
            if (playerData.canIgnoreClaim(claim)) {
                return processResult(claim, targetPermission, "ignore", Tristate.TRUE, user);
            }
        }
        if (user != null) {
            // check if rented
            if (claim.getEconomyData() != null && claim.getEconomyData().isRented() && 
                    flag != Flags.COMMAND_EXECUTE && 
                    flag != Flags.COMMAND_EXECUTE_PVP && 
                    flag != Flags.ENTER_CLAIM && 
                    flag != Flags.EXIT_CLAIM &&
                    flag != Flags.ENTITY_TELEPORT_FROM &&
                    flag != Flags.ENTITY_TELEPORT_TO &&
                    flag != Flags.INTERACT_INVENTORY_CLICK) {
                if (claim.getOwnerUniqueId() != null && user != null && claim.getOwnerUniqueId().equals(user.getUniqueId())) {
                    return processResult(claim, targetPermission, "rent-owner-deny", Tristate.FALSE, user);
                }

                if (EconomyUtil.getInstance().isRenter(claim, user) && (targetPermission.contains("interact") || targetPermission.contains("block"))) {
                    if ((targetPermission.contains("interact") || targetPermission.contains("block-place"))) {
                        final boolean hasInventory = NMSUtil.getInstance().isTileInventory(location) || location.getBlock().getType() == Material.ENDER_CHEST;
                        if (!hasInventory || flag == Flags.BLOCK_PLACE) {
                            return processResult(claim, targetPermission, "renter-interact", Tristate.TRUE, user);
                        }
                        // check entity interactions
                        if (targetPermission.contains("interact-entity") && target instanceof LivingEntity) {
                            // Allow interaction with all living entities
                            return processResult(claim, targetPermission, "renter-interact", Tristate.TRUE, user);
                        }
                    }

                    // Allow renters to break/change their own blocks
                    final GDClaimManager claimWorldManager = GriefDefenderPlugin.getInstance().dataStore.getClaimWorldManager(location.getWorld().getUID());
                    final GDChunk gdChunk = claimWorldManager.getChunk(location.getBlock().getChunk());
                    final GDPermissionUser owner = gdChunk.getBlockOwner(location);
                    if (owner != null && owner.getUniqueId().equals(user.getUniqueId())) {
                        // allow
                        return processResult(claim, targetPermission, "renter-owned", Tristate.TRUE, user);
                    }
                }
            }
            if (type != null) {
                if (((GDClaim) claim).isUserTrusted(user, type)) {
                    // check persisted flags
                    if (!claim.isWilderness()) {
                        if ((claim.isAdminClaim() && !user.getInternalPlayerData().canManageAdminClaims) || !user.getUniqueId().equals(claim.getOwnerUniqueId())) {
                            final Tristate result = getUserPermission(user, claim, targetPermission, PermissionDataType.USER_PERSISTENT);
                            if (result != Tristate.UNDEFINED) {
                                return processResult(claim, targetPermission, result, user);
                            }
                        }
                    }
                    return processResult(claim, targetPermission, type.getName().toLowerCase(), Tristate.TRUE, permissionHolder);
                }
            }
            return getUserPermission(user, claim, targetPermission, PermissionDataType.ALL);
        }

        return getClaimFlagPermission(claim, targetPermission);
    }

    private Tristate getUserPermission(GDPermissionHolder holder, Claim claim, String permission, PermissionDataType dataType) {
        final List<Claim> inheritParents = claim.getInheritedParents();
        final Set<Context> contexts = new HashSet<>();
        contexts.addAll(this.eventContexts);

        for (Claim parentClaim : inheritParents) {
            GDClaim parent = (GDClaim) parentClaim;
            // check parent context
            contexts.add(parent.getContext());
            Tristate value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, holder, permission, contexts, dataType);
            if (value != Tristate.UNDEFINED) {
                return processResult(claim, permission, value, holder);
            }

            contexts.remove(parent.getContext());
        }

        // Check claim context
        contexts.add(claim.getContext());
        Tristate value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, holder, permission, contexts, dataType);
        if (value != Tristate.UNDEFINED) {
            return processResult(claim, permission, value, holder);
        }
        if (dataType == PermissionDataType.USER_PERSISTENT) {
            // don't log, just return result
            return value;
        }


        if (holder == GriefDefenderPlugin.DEFAULT_HOLDER) {
            contexts.remove(claim.getContext());
            return getFlagDefaultPermission(claim, permission, contexts);
        }

        return getClaimFlagPermission(claim, permission, contexts, inheritParents);
    }

    private Tristate getClaimFlagPermission(Claim claim, String permission) {
        return this.getClaimFlagPermission(claim, permission, new HashSet<>(), null);
    }

    private Tristate getClaimFlagPermission(Claim claim, String permission, Set<Context> contexts, List<Claim> inheritParents) {
        if (contexts.isEmpty()) {
            if (inheritParents == null) {
                inheritParents = claim.getInheritedParents();
            }
            contexts.addAll(this.eventContexts);
            for (Claim parentClaim : inheritParents) {
                GDClaim parent = (GDClaim) parentClaim;
                // check parent context
                contexts.add(parent.getContext());
                Tristate value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, GriefDefenderPlugin.DEFAULT_HOLDER, permission, contexts, PermissionDataType.PERSISTENT);
                if (value != Tristate.UNDEFINED) {
                    return processResult(claim, permission, value, GriefDefenderPlugin.DEFAULT_HOLDER);
                }

                contexts.remove(parent.getContext());
            }
            contexts.add(claim.getContext());
        }

        Tristate value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, GriefDefenderPlugin.DEFAULT_HOLDER, permission, contexts, PermissionDataType.PERSISTENT);
        if (value != Tristate.UNDEFINED) {
            return processResult(claim, permission, value, GriefDefenderPlugin.DEFAULT_HOLDER);
        }

        return getFlagDefaultPermission(claim, permission, contexts);
    }

    // Only uses world and claim type contexts
    private Tristate getFlagDefaultPermission(Claim claim, String permission, Set<Context> contexts) {
        contexts.add(claim.getDefaultTypeContext());
        Tristate value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, GriefDefenderPlugin.DEFAULT_HOLDER, permission, contexts, PermissionDataType.PERSISTENT);
        if (value != Tristate.UNDEFINED) {
            return processResult(claim, permission, value, GriefDefenderPlugin.DEFAULT_HOLDER);
        }
        contexts.remove(claim.getDefaultTypeContext());
        if (!claim.isWilderness()) {
            contexts.add(ClaimContexts.GLOBAL_DEFAULT_CONTEXT);
            contexts.add(ClaimContexts.USER_DEFAULT_CONTEXT);
            value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, GriefDefenderPlugin.DEFAULT_HOLDER, permission, contexts, PermissionDataType.ALL);
            if (value != Tristate.UNDEFINED) {
                return processResult(claim, permission, value, GriefDefenderPlugin.DEFAULT_HOLDER);
            }
            contexts.remove(ClaimContexts.USER_DEFAULT_CONTEXT);
        } else {
            contexts.add(ClaimContexts.GLOBAL_DEFAULT_CONTEXT);
            value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, GriefDefenderPlugin.DEFAULT_HOLDER, permission, contexts, PermissionDataType.ALL);
            if (value != Tristate.UNDEFINED) {
                return processResult(claim, permission, value, GriefDefenderPlugin.DEFAULT_HOLDER);
            }
        }

        contexts.remove(ClaimContexts.GLOBAL_DEFAULT_CONTEXT);
        contexts.add(claim.getDefaultTypeContext());
        value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, GriefDefenderPlugin.DEFAULT_HOLDER, permission, contexts, PermissionDataType.TRANSIENT);
        if (value != Tristate.UNDEFINED) {
            return processResult(claim, permission, value, GriefDefenderPlugin.DEFAULT_HOLDER);
        }

        return processResult(claim, permission, Tristate.UNDEFINED, GriefDefenderPlugin.DEFAULT_HOLDER);
    }

    private Tristate getFlagOverride(Claim claim, GDPermissionHolder permissionHolder, GDPlayerData playerData, String flagPermission) {
        if (!((GDClaim) claim).getInternalClaimData().allowFlagOverrides()) {
            return Tristate.UNDEFINED;
        }

        Player player = null;
        Set<Context> contexts = new HashSet<>();
        if (claim.isAdminClaim()) {
            contexts.add(ClaimContexts.ADMIN_OVERRIDE_CONTEXT);
        } else if (claim.isTown()) {
            contexts.add(ClaimContexts.TOWN_OVERRIDE_CONTEXT);
        } else if (claim.isBasicClaim()) {
            contexts.add(ClaimContexts.BASIC_OVERRIDE_CONTEXT);
        } else if (claim.isWilderness()) {
            contexts.add(ClaimContexts.WILDERNESS_OVERRIDE_CONTEXT);
            player = permissionHolder instanceof GDPermissionUser ? ((GDPermissionUser) permissionHolder).getOnlinePlayer() : null;
        }
        if (!claim.isWilderness()) {
            contexts.add(ClaimContexts.USER_OVERRIDE_CONTEXT);
        }

        contexts.add(((GDClaim) claim).getWorldContext());
        contexts.add(ClaimContexts.GLOBAL_OVERRIDE_CONTEXT);
        contexts.addAll(this.eventContexts);

        Tristate value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, permissionHolder, flagPermission, contexts, PermissionDataType.PERSISTENT);
        if (value == Tristate.UNDEFINED) {
            // check claim owner parent override
            /*final List<Claim> inheritParents = claim.getInheritedParents();
            contexts = new HashSet<>();
            contexts.add(((GDClaim) claim).getWorldContext());
            contexts.addAll(this.eventContexts);
            for (Claim parentClaim : inheritParents) {
                GDClaim parent = (GDClaim) parentClaim;
                // check parent override claim context
                contexts.add(parent.getOverrideClaimContext());
                value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, permissionHolder, flagPermission, contexts);
                if (value != Tristate.UNDEFINED) {
                    if (value == Tristate.FALSE) {
                        this.eventMessage = MessageCache.getInstance().PERMISSION_OVERRIDE_DENY;
                    }
                    return processResult(claim, flagPermission, value, permissionHolder);
                }

                contexts.remove(parent.getOverrideClaimContext());
            }*/

            // check claim owner override
            contexts = new HashSet<>();
            contexts.add(((GDClaim) claim).getWorldContext());
            contexts.addAll(this.eventContexts);
            contexts.add(claim.getOverrideClaimContext());
            value = PermissionUtil.getInstance().getPermissionValue((GDClaim) claim, permissionHolder, flagPermission, contexts, PermissionDataType.PERSISTENT);
        }
        if (value != Tristate.UNDEFINED) {
            if (value == Tristate.FALSE) {
                this.eventMessage = MessageCache.getInstance().PERMISSION_OVERRIDE_DENY;
            }
            return processResult(claim, flagPermission, value, permissionHolder);
        }

        return Tristate.UNDEFINED;
    }

    public Tristate processResult(Claim claim, String permission, Tristate permissionValue, GDPermissionHolder permissionHolder) {
        return processResult(claim, permission, null, permissionValue, permissionHolder);
    }

    public Tristate processResult(Claim claim, String permission, String trust, Tristate permissionValue, GDPermissionHolder permissionHolder) {
        if (GriefDefenderPlugin.debugActive) {
            // Use the event subject always if available
            // This prevents debug showing 'default' for users
            if (eventSubject != null) {
                permissionHolder = eventSubject;
            } else if (permissionHolder == null) {
                final Object source = GDCauseStackManager.getInstance().getCurrentCause().root();
                if (source instanceof GDPermissionUser) {
                    permissionHolder = (GDPermissionUser) source;
                } else {
                    permissionHolder = GriefDefenderPlugin.DEFAULT_HOLDER;
                }
            }

            if (this.currentEvent != null && (this.currentEvent instanceof BlockPhysicsEvent)) {
                if (((GDClaim) claim).getWorld().getTime() % 100 != 0L) {
                    return permissionValue;
                }
            }

            GriefDefenderPlugin.addEventLogEntry(this.currentEvent, claim, this.eventLocation, this.eventSourceId, this.eventTargetId, this.eventSubject == null ? permissionHolder : this.eventSubject, permission, trust, permissionValue, this.eventContexts);
        }


        if (eventPlayerData != null && eventPlayerData.eventResultCache != null) {
            final Flag flag = FlagRegistryModule.getInstance().getById(permission).orElse(null);
            if (flag != null) {
                eventPlayerData.eventResultCache = new EventResultCache((GDClaim) claim, flag.getName().toLowerCase(), permissionValue);
            }
        }
        return permissionValue;
    }

    public void processEventLog(Event event, Location location, Claim claim, String permission, Object source, Object target, GDPermissionHolder user, String trust, Tristate value) {
        final String sourceId = this.getPermissionIdentifier(source, true);
        final String targetId = this.getPermissionIdentifier(target);
        final Set<Context> sourceContexts = this.getPermissionContexts((GDClaim) claim, source, true);
        if (sourceContexts == null) {
            return;
        }

        final Set<Context> targetContexts = this.getPermissionContexts((GDClaim) claim, target, false);
        if (targetContexts == null) {
            return;
        }

        final Set<Context> contexts = new HashSet<>();
        contexts.addAll(sourceContexts);
        contexts.addAll(targetContexts);
        contexts.add(((GDClaim) claim).getWorldContext());
        if (GriefDefenderPlugin.debugActive) {
            if (user == null) {
                final Object root = GDCauseStackManager.getInstance().getCurrentCause().root();
                if (source instanceof GDPermissionUser) {
                    user = (GDPermissionUser) root;
                } else {
                    user = GriefDefenderPlugin.DEFAULT_HOLDER;
                }
            }

            GriefDefenderPlugin.addEventLogEntry(event, claim, location, sourceId, targetId, user, permission, trust.toLowerCase(), value, contexts);
        }
    }

    public String getPermissionIdentifier(Object obj) {
        return getPermissionIdentifier(obj, false);
    }

    public String getPermissionIdentifier(Object obj, boolean isSource) {
        if (obj != null) {
            if (obj instanceof Entity) {
                Entity targetEntity = (Entity) obj;

                if (targetEntity instanceof Item) {
                    return getPermissionIdentifier(((Item) targetEntity).getItemStack(), isSource);
                }
                if (targetEntity.getType() == null) {
                    // Plugin sending fake player and violating API contract so just ignore...
                    return "unknown";
                }

                final String name = targetEntity.getType().getName() == null ? targetEntity.getType().name().toLowerCase() : targetEntity.getType().getName();
                final GDEntityType type = EntityTypeRegistryModule.getInstance().getByBukkitType(targetEntity);
                if (type == null) {
                    // Should never happen
                    return name;
                }

                String id = type.getId();

                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof Block) {
                final String id = BlockTypeRegistryModule.getInstance().getNMSKey((Block) obj);
                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof BlockState) {
                final BlockState blockstate = (BlockState) obj;
                final String id = BlockTypeRegistryModule.getInstance().getNMSKey(blockstate);
                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof Material) {
                final String id = ((Material) obj).name().toLowerCase();
                return populateEventSourceTarget(id, isSource);
            } /*else if (obj instanceof TileEntity) {
                TileEntity tileEntity = (TileEntity) obj;
                final String id = tileEntity.getMinecraftKeyString();
                return populateEventSourceTarget(id, isSource);
            }*/ else if (obj instanceof Inventory) {
                final String id = ((Inventory) obj).getType().name().toLowerCase();
                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof InventoryType) {
                final String id = ((InventoryType) obj).name().toLowerCase();
                populateEventSourceTarget(id, isSource);
                return id;
            } else if (obj instanceof Item) {
                
            } else if (obj instanceof ItemStack) {
                final ItemStack itemstack = (ItemStack) obj;
                String id = ItemTypeRegistryModule.getInstance().getNMSKey(itemstack);
                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof DamageCause) {
                final DamageCause damageCause = (DamageCause) obj;
                String id = damageCause.name().toLowerCase();
                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof TeleportCause) {
                final TeleportCause teleportCause = (TeleportCause) obj;
                String id = teleportCause.name().toLowerCase();
                return populateEventSourceTarget(id, isSource);
            } else if (obj instanceof SpawnReason) {
                return populateEventSourceTarget("spawnreason:" + ((SpawnReason) obj).name().toLowerCase(), isSource);
            } else if (obj instanceof CreatureSpawner) {
                final CreatureSpawner spawner = (CreatureSpawner) obj;
                return this.getPermissionIdentifier(spawner.getBlock());
            }  else if (obj instanceof String) {
                final String id = obj.toString().toLowerCase();
                return populateEventSourceTarget(id, isSource);
            }
        }

        populateEventSourceTarget("none", isSource);
        return "";
    }

    public Set<Context> getPermissionContexts(GDClaim claim, Object obj, boolean isSource) {
        final Set<Context> contexts = new HashSet<>();
        if (obj == null) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_ANY);
            } else {
                contexts.add(ContextGroups.TARGET_ANY);
            }
            return contexts;
        }

        if (obj instanceof Entity) {
            Entity targetEntity = (Entity) obj;

            if (targetEntity instanceof Item) {
                return getPermissionContexts(claim, ((Item) targetEntity).getItemStack(), isSource);
            }
            if (targetEntity.getType() == null) {
                // Plugin sending fake player and violating API contract so just ignore...
                return contexts;
            }

            final GDEntityType type = EntityTypeRegistryModule.getInstance().getByBukkitType(targetEntity);
            String id = type.getId();

            if (!(targetEntity instanceof Player)) {
                addCustomEntityTypeContexts(targetEntity, id, contexts, type, isSource);
            } else {
                final Player player = (Player) targetEntity;
                if (PlayerUtil.getInstance().isFakePlayer(player)) { 
                    final String modId = EntityTypeRegistryModule.getInstance().findModIdFromBukkit(targetEntity);
                    id = modId + ":fakeplayer_" + EntityTypeRegistryModule.getInstance().getFriendlyName(targetEntity.getName());
                }
            }

            if (this.isObjectIdBanned(claim, id, BanType.ENTITY)) {
                return null;
            }
            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof Block) {
            final Block block = (Block) obj;
            final String id = BlockTypeRegistryModule.getInstance().getNMSKey(block);
            this.addBlockContexts(contexts, block, isSource);
            if (this.isObjectIdBanned(claim, id, BanType.BLOCK)) {
                return null;
            }

            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof BlockState) {
            final BlockState blockstate = (BlockState) obj;
            final String id = BlockTypeRegistryModule.getInstance().getNMSKey(blockstate);
            this.addBlockContexts(contexts, blockstate.getBlock(), isSource);
            if (this.isObjectIdBanned(claim, id, BanType.BLOCK)) {
                return null;
            }

            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof Material) {
            final String id = ((Material) obj).name().toLowerCase();
            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof Inventory) {
            final String id = ((Inventory) obj).getType().name().toLowerCase();
            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof InventoryType) {
            final String id = ((InventoryType) obj).name().toLowerCase();
            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof ItemStack) {
            final ItemStack itemstack = (ItemStack) obj;
            if (NMSUtil.getInstance().isItemFood(itemstack)) {
                if (isSource) {
                    contexts.add(ContextGroups.SOURCE_FOOD);
                } else {
                    contexts.add(ContextGroups.TARGET_FOOD);
                }
            }
            if (NMSUtil.getInstance().isItemHanging(itemstack)) {
                if (isSource) {
                    contexts.add(ContextGroups.SOURCE_HANGING);
                } else {
                    contexts.add(ContextGroups.TARGET_HANGING);
                }
            }
            if (NMSUtil.getInstance().isItemBoat(itemstack) || NMSUtil.getInstance().isItemMinecart(itemstack)) {
                if (isSource) {
                    contexts.add(ContextGroups.SOURCE_VEHICLE);
                } else {
                    contexts.add(ContextGroups.TARGET_VEHICLE);
                }
            }
            String id = ItemTypeRegistryModule.getInstance().getNMSKey(itemstack);
            if (this.isObjectIdBanned(claim, id, BanType.ITEM)) {
                return null;
            }

            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof DamageCause) {
            final DamageCause damageCause = (DamageCause) obj;
            String id = damageCause.name().toLowerCase();
            return populateEventSourceTargetContext(contexts, id, isSource);
        } else if (obj instanceof SpawnReason) {
            return populateEventSourceTargetContext(contexts, "spawnreason:" + ((SpawnReason) obj).name().toLowerCase(), isSource);
        } else if (obj instanceof CreatureSpawner) {
            final CreatureSpawner spawner = (CreatureSpawner) obj;
            return this.getPermissionContexts(claim, spawner.getBlock(), isSource);
        }  else if (obj instanceof String) {
            final String id = obj.toString().toLowerCase();
            return populateEventSourceTargetContext(contexts, id, isSource);
        }

        return contexts;
    }

    public boolean isObjectIdBanned(GDClaim claim, String id, BanType type) {
        if (id.equalsIgnoreCase("player")) {
            return false;
        }

        GDPermissionUser user = null;
        if (this.eventSubject != null && this.eventSubject instanceof GDPermissionUser) {
            user = (GDPermissionUser) this.eventSubject;
            if (user.getInternalPlayerData() != null && user.getInternalPlayerData().canIgnoreClaim(claim)) {
                return false;
            }
        }

        final String permission = StringUtils.replace(id, ":", ".");
        Component banReason = null;
        final BanCategory banCategory = GriefDefenderPlugin.getGlobalConfig().getConfig().bans;
        if (type == BanType.BLOCK) {
            for (Entry<String, Component> banId : banCategory.getBlockMap().entrySet()) {
                if (FilenameUtils.wildcardMatch(id, banId.getKey())) {
                    banReason = GriefDefenderPlugin.getGlobalConfig().getConfig().bans.getBlockBanReason(banId.getKey());
                    if (banReason != null && banReason.equals(TextComponent.empty())) {
                        banReason = MessageStorage.MESSAGE_DATA.getMessage(MessageStorage.PERMISSION_BAN_BLOCK, 
                                ImmutableMap.of("id", TextComponent.of(id, TextColor.GOLD)));
                    }
                    break;
                }
            }
        } else if (type == BanType.ITEM) {
            for (Entry<String, Component> banId : banCategory.getItemMap().entrySet()) {
                if (FilenameUtils.wildcardMatch(id, banId.getKey())) {
                    banReason = GriefDefenderPlugin.getGlobalConfig().getConfig().bans.getItemBanReason(banId.getKey());
                    if (banReason != null && banReason.equals(TextComponent.empty())) {
                        banReason = MessageStorage.MESSAGE_DATA.getMessage(MessageStorage.PERMISSION_BAN_ITEM, 
                                ImmutableMap.of("id", TextComponent.of(id, TextColor.GOLD)));
                    }
                }
            }
        } else if (type == BanType.ENTITY) {
            for (Entry<String, Component> banId : banCategory.getEntityMap().entrySet()) {
                if (FilenameUtils.wildcardMatch(id, banId.getKey())) {
                    banReason = GriefDefenderPlugin.getGlobalConfig().getConfig().bans.getEntityBanReason(banId.getKey());
                    if (banReason != null && banReason.equals(TextComponent.empty())) {
                        banReason = MessageStorage.MESSAGE_DATA.getMessage(MessageStorage.PERMISSION_BAN_ENTITY, 
                                ImmutableMap.of("id", TextComponent.of(id, TextColor.GOLD)));
                    }
                }
            }
        }

        if (banReason != null && user != null) {
            final Player player = user.getOnlinePlayer();
            if (player != null) {
                if (banReason.equals(TextComponent.empty())) {
                    banReason = MessageStorage.MESSAGE_DATA.getMessage(MessageStorage.PERMISSION_BAN_BLOCK, 
                            ImmutableMap.of("id", id));
                }
                TextAdapter.sendComponent(player, banReason);
                this.processResult(claim, permission, "banned", Tristate.FALSE, user);
                return true;
            }
        }
        if (banReason != null) {
            // Detected ban
            this.processResult(claim, permission, "banned", Tristate.FALSE, this.eventSubject);
            return true;
        }
        return false;
    }

    public void addCustomEntityTypeContexts(Entity targetEntity, String id, Set<Context> contexts, GDEntityType type, boolean isSource) {
        if (isSource) {
            contexts.add(ContextGroups.SOURCE_ANY);
            contexts.add(new Context(ContextKeys.SOURCE, "#" + type.getModId().toLowerCase() + ":any"));
        } else {
            contexts.add(ContextGroups.TARGET_ANY);
            contexts.add(new Context(ContextKeys.TARGET, "#" + type.getModId().toLowerCase() + ":any"));
        }
        // check vehicle
        if (targetEntity instanceof Vehicle) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_VEHICLE);
            } else {
                contexts.add(ContextGroups.TARGET_VEHICLE);
            }
        }
        // pixelmon
        if (targetEntity.getType() != null && targetEntity.getType().name().equalsIgnoreCase("pixelmon_pixelmon") || targetEntity.getType().name().equalsIgnoreCase("pixelmon")) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_PIXELMON);
            } else {
                contexts.add(ContextGroups.TARGET_PIXELMON);
            }
        }
        final String creatureType = type.getEnumCreatureTypeId();
        if (creatureType == null) {
            return;
        }

        final String modId = type.getModId().toLowerCase();
        if (creatureType.contains("animal")) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_ANIMAL);
                contexts.add(new Context(ContextKeys.SOURCE, "#" + modId + ":animal"));
            } else {
                contexts.add(ContextGroups.TARGET_ANIMAL);
                contexts.add(new Context(ContextKeys.TARGET, "#" + modId + ":animal"));
            }
            this.checkPetContext(targetEntity, modId, contexts, isSource);
        } else if (creatureType.contains("aquatic")) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_AQUATIC);
                contexts.add(new Context(ContextKeys.SOURCE, "#" + modId + ":aquatic"));
            } else {
                contexts.add(ContextGroups.TARGET_AQUATIC);
                contexts.add(new Context(ContextKeys.TARGET, "#" + modId + ":aquatic"));
            }
            this.checkPetContext(targetEntity, modId, contexts, isSource);
        } else if (creatureType.contains("monster")) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_MONSTER);
                contexts.add(new Context(ContextKeys.SOURCE, "#" + modId + ":monster"));
            } else {
                contexts.add(ContextGroups.TARGET_MONSTER);
                contexts.add(new Context(ContextKeys.TARGET, "#" + modId + ":monster"));
            }
        }  else if (creatureType.contains("ambient")) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_AMBIENT);
                contexts.add(new Context(ContextKeys.SOURCE, "#" + modId + ":ambient"));
            } else {
                contexts.add(ContextGroups.TARGET_AMBIENT);
                contexts.add(new Context(ContextKeys.TARGET, "#" + modId + ":ambient"));
            }
            this.checkPetContext(targetEntity, modId, contexts, isSource);
        } else {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_MISC);
                contexts.add(new Context(ContextKeys.SOURCE, "#" + modId + ":misc"));
            } else {
                contexts.add(ContextGroups.TARGET_MISC);
                contexts.add(new Context(ContextKeys.TARGET, "#" + modId + ":misc"));
            }
        }
    }

    private void checkPetContext(Entity targetEntity, String modId, Set<Context> contexts, boolean isSource) {
        if (this.eventSubject != null && this.eventSubject instanceof GDPermissionUser) {
            final GDPermissionUser user = (GDPermissionUser) this.eventSubject;
            final UUID uuid = NMSUtil.getInstance().getTameableOwnerUUID(targetEntity);
            if (uuid != null && uuid.equals(user.getUniqueId())) {
                if (isSource) {
                    contexts.add(ContextGroups.SOURCE_PET);
                    if (modId != null && !modId.isEmpty()) {
                        contexts.add(new Context(ContextKeys.SOURCE, "#" + modId + ":pet"));
                    }
                } else {
                    contexts.add(ContextGroups.TARGET_PET);
                    if (modId != null && !modId.isEmpty()) {
                        contexts.add(new Context(ContextKeys.TARGET, "#" + modId + ":pet"));
                    }
                }
            }
        }
    }

    private void addPlayerContexts(Player player, Set<Context> contexts, Flag flag) {
        Context usedItemContext = null;
        for (Context context : contexts) {
            if (context.getKey().equals(ContextKeys.USED_ITEM)) {
                usedItemContext = context;
                break;
            }
        }
        if(usedItemContext == null) {
            // special case
            if (this.currentEvent instanceof PlayerBucketEvent) {
                final PlayerBucketEvent bucketEvent = (PlayerBucketEvent) this.currentEvent;
                contexts.add(new Context(ContextKeys.USED_ITEM, "minecraft:" + bucketEvent.getBucket().name().toLowerCase()));
            } else {
                final ItemStack stack = NMSUtil.getInstance().getActiveItem(player, this.currentEvent);
                if (stack != null && stack.getType() != Material.AIR) {
                    final String stackId = getPermissionIdentifier(stack);
                    contexts.add(new Context(ContextKeys.USED_ITEM, stackId));
                    if (stack.getItemMeta() != null && stack.getItemMeta().getDisplayName() != null) {
                        String itemName = stack.getItemMeta().getDisplayName().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
                        if (itemName != null && !itemName.isEmpty()) {
                            if (!itemName.contains(":")) {
                                itemName = "minecraft:" + itemName;
                            }
                            contexts.add(new Context("item_name", itemName));
                        }
                    }
                    // populate item tag
                    if (GriefDefenderPlugin.getInstance().getTagProvider() != null) {
                        final Set<Context> tagContexts = GriefDefenderPlugin.getInstance().getTagProvider().getTagMap().get(stackId);
                        if (tagContexts != null) {
                            for (Context context : tagContexts) {
                                contexts.add(new Context(ContextKeys.USED_ITEM, "#" + context.getKey() + ":" + context.getValue()));
                                if (context.getKey().equalsIgnoreCase("minecraft")) {
                                    contexts.add(new Context(ContextKeys.USED_ITEM, "#" + context.getValue()));
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // populate item tag
            if (GriefDefenderPlugin.getInstance().getTagProvider() != null) {
                final Set<Context> tagContexts = GriefDefenderPlugin.getInstance().getTagProvider().getTagMap().get(usedItemContext.getValue());
                if (tagContexts != null) {
                    for (Context context : tagContexts) {
                        contexts.add(new Context(ContextKeys.USED_ITEM, "#" + context.getKey() + ":" + context.getValue()));
                        if (context.getKey().equalsIgnoreCase("minecraft")) {
                            contexts.add(new Context(ContextKeys.USED_ITEM, "#" + context.getValue()));
                        }
                    }
                }
            }
        }
        final ItemStack helmet = player.getInventory().getHelmet();
        final ItemStack chestplate = player.getInventory().getChestplate();
        final ItemStack leggings = player.getInventory().getLeggings();
        final ItemStack boots = player.getInventory().getBoots();
        if (helmet != null) {
            contexts.add(new Context("helmet", getPermissionIdentifier(helmet)));
        }
        if (chestplate != null) {
            contexts.add(new Context("chestplate", getPermissionIdentifier(chestplate)));
        }
        if (leggings != null) {
            contexts.add(new Context("leggings", getPermissionIdentifier(leggings)));
        }
        if (boots != null) {
            contexts.add(new Context("boots", getPermissionIdentifier(boots)));
        }
    }

    private Set<Context> addBlockContexts(Set<Context> contexts, Block block, boolean isSource) {
        Matcher matcher = BLOCKSTATE_PATTERN.matcher(NMSUtil.getInstance().getBlockDataString((Block) block));
        if (matcher.find()) {
            final String properties[] = matcher.group(0).split(",");
            for (String property : properties) {
                contexts.add(new Context(ContextKeys.STATE, property.replace("=", ":")));
            }
        }

        if (NMSUtil.getInstance().isBlockCrops(block)) {
            if (isSource) {
                contexts.add(ContextGroups.SOURCE_CROPS);
            } else {
                contexts.add(ContextGroups.TARGET_CROPS);
            }
        }
        return contexts;
    }

    public String getIdentifierWithoutMeta(String targetId) {
        Matcher m = PATTERN_META.matcher(targetId);
        String targetMeta = "";
        if (m.find()) {
            targetMeta = m.group(0);
            targetId = StringUtils.replace(targetId, targetMeta, "");
        }
        return targetId;
    }

    private Set<Context> populateEventSourceTargetContext(Set<Context> contexts, String id, boolean isSource) {
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        contexts = this.populateTagContextsForId(contexts, id, isSource);
        final String[] parts = id.split(":");
        final String modId = parts[0];
        if (isSource) {
            this.eventSourceId = id.toLowerCase();
            contexts.add(new Context("source", this.eventSourceId));
            contexts.add(new Context("source", modId + ":any"));
        } else {
            this.eventTargetId = id.toLowerCase();
            contexts.add(new Context("target", this.eventTargetId));
            contexts.add(new Context("target", modId + ":any"));
        }

        return contexts;
    }

    public Set<Context> populateTagContextsForId(Set<Context> contexts, String id, boolean isSource) {
        if (GriefDefenderPlugin.getInstance().getTagProvider() == null) {
            return contexts;
        }

        final Set<Context> tagContexts = GriefDefenderPlugin.getInstance().getTagProvider().getTagMap().get(id);
        if (tagContexts == null) {
            return contexts;
        }

        for (Context context : tagContexts) {
            if (isSource) {
                contexts.add(new Context(ContextKeys.SOURCE, "#" + context.getKey() + ":" + context.getValue()));
                if (context.getKey().equalsIgnoreCase("minecraft")) {
                    contexts.add(new Context(ContextKeys.SOURCE, "#" + context.getValue()));
                }
            } else {
                contexts.add(new Context(ContextKeys.TARGET, "#" + context.getKey() + ":" + context.getValue()));
                if (context.getKey().equalsIgnoreCase("minecraft")) {
                    contexts.add(new Context(ContextKeys.TARGET, "#" + context.getValue()));
                }
            }
        }

        return contexts;
    }

    private String populateEventSourceTarget(String id, boolean isSource) {
        if (this.blacklistCheck) {
            return id;
        }

        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        String[] parts = id.split(":");
        if (parts != null && parts.length == 3) {
            if (parts[0].equals(parts[1])) {
                id = parts[1] + ":" + parts[2];
            }
        }
        if (isSource) {
            this.eventSourceId = id.toLowerCase();
        } else {
            this.eventTargetId = id.toLowerCase();
        }

        return id;
    }

    @Override
    public CompletableFuture<PermissionResult> clearAllFlagPermissions(Subject subject) {
        CompletableFuture<PermissionResult> result = new CompletableFuture<>();
        if (subject == null) {
            result.complete(new GDPermissionResult(ResultTypes.SUBJECT_DOES_NOT_EXIST));
            return result;
        }

        GDFlagPermissionEvent.ClearAll event = new GDFlagPermissionEvent.ClearAll(subject);
        GriefDefender.getEventManager().post(event);
        if (event.cancelled()) {
            result.complete(new GDPermissionResult(ResultTypes.EVENT_CANCELLED, event.getMessage().orElse(null)));
            return result;
        }

        for (Map.Entry<Set<Context>, Map<String, Boolean>> mapEntry : PermissionUtil.getInstance().getPermanentPermissions((GDPermissionHolder) subject).entrySet()) {
            final Set<Context> contextSet = mapEntry.getKey();
            for (Context context : contextSet) {
                if (context.getValue().equals(subject.getIdentifier())) {
                    PermissionUtil.getInstance().clearPermissions((GDPermissionHolder) subject, context);
                }
            }
        }

        result.complete(new GDPermissionResult(ResultTypes.SUCCESS));
        return result;
    }

    @Override
    public CompletableFuture<PermissionResult> clearFlagPermissions(Set<Context> contexts) {
        return clearFlagPermissions(GriefDefenderPlugin.DEFAULT_HOLDER, contexts);
    }

    @Override
    public CompletableFuture<PermissionResult> clearFlagPermissions(Subject subject, Set<Context> contexts) {
        CompletableFuture<PermissionResult> result = new CompletableFuture<>();
        if (subject == null) {
            result.complete(new GDPermissionResult(ResultTypes.SUBJECT_DOES_NOT_EXIST));
        }

        GDFlagPermissionEvent.Clear event = new GDFlagPermissionEvent.Clear(subject, contexts);
        GriefDefender.getEventManager().post(event);
        if (event.cancelled()) {
            result.complete(new GDPermissionResult(ResultTypes.EVENT_CANCELLED, event.getMessage().orElse(null)));
            return result;
        }

        PermissionUtil.getInstance().clearPermissions((GDPermissionHolder) subject, contexts);
        result.complete(new GDPermissionResult(ResultTypes.SUCCESS));
        return result;
    }

    @Override
    public CompletableFuture<PermissionResult> setFlagPermission(Flag flag, Tristate value, Set<Context> contexts) {
        return setPermission(GriefDefenderPlugin.DEFAULT_HOLDER, flag, value, contexts);
    }

    public CompletableFuture<PermissionResult> setPermission(Subject subject, Flag flag, Tristate value, Set<Context> contexts) {
        CompletableFuture<PermissionResult> result = new CompletableFuture<>();

        GDFlagPermissionEvent.Set event = new GDFlagPermissionEvent.Set(subject, flag, value, contexts);
        GriefDefender.getEventManager().post(event);
        if (event.cancelled()) {
            result.complete(new GDPermissionResult(ResultTypes.EVENT_CANCELLED, event.getMessage().orElse(null)));
            return result;
        }

        result.complete(PermissionUtil.getInstance().setPermissionValue((GDPermissionHolder) subject, flag, value, contexts));
        return result;
    }

    // internal
    public CompletableFuture<PermissionResult> setPermission(Claim claim, GDPermissionHolder subject, Flag flag, String target, Tristate value, Set<Context> contexts) {
        if (target.equalsIgnoreCase("any:any")) {
            target = "any";
        }

        CompletableFuture<PermissionResult> result = new CompletableFuture<>();
        if (flag != Flags.COMMAND_EXECUTE && flag != Flags.COMMAND_EXECUTE_PVP) {
            String[] parts = target.split(":");
            if (!target.startsWith("#") && parts.length > 1 && parts[0].equalsIgnoreCase("minecraft")) {
                target = parts[1];
            }
            if (target != null && !GriefDefenderPlugin.ID_MAP.contains(target)) {
                result.complete(new GDPermissionResult(ResultTypes.TARGET_NOT_VALID));
                return result;
            }
        }

        contexts.add(new Context(ContextKeys.TARGET, target));
        GDFlagPermissionEvent.Set event = new GDFlagPermissionEvent.Set(subject, flag, value, contexts);
        GriefDefender.getEventManager().post(event);
        if (event.cancelled()) {
            result.complete(new GDPermissionResult(ResultTypes.EVENT_CANCELLED, event.getMessage().orElse(null)));
            return result;
        }

        final GDPermissionUser user = GDCauseStackManager.getInstance().getCurrentCause().first(GDPermissionUser.class).orElse(null);
        CommandSender commandSource = user != null && user.getOnlinePlayer() != null ? user.getOnlinePlayer() : Bukkit.getConsoleSender();
        result.complete(CommandHelper.addFlagPermission(commandSource, subject, claim, flag, target, value, contexts));
        return result;
    }

    @Override
    public Tristate getFlagPermissionValue(Flag flag, Set<Context> contexts) {
        return getPermissionValue(GriefDefenderPlugin.DEFAULT_HOLDER, flag, contexts);
    }

    public Tristate getPermissionValue(GDPermissionHolder subject, Flag flag, Set<Context> contexts) {
        return PermissionUtil.getInstance().getPermissionValue(subject, flag.getPermission(), contexts);
    }

    @Override
    public Map<String, Boolean> getFlagPermissions(Set<Context> contexts) {
        return getFlagPermissions(GriefDefenderPlugin.DEFAULT_HOLDER, contexts);
    }

    @Override
    public Map<String, Boolean> getFlagPermissions(Subject subject, Set<Context> contexts) {
        if (subject == null) {
            return new HashMap<>();
        }
        return PermissionUtil.getInstance().getPermissions((GDPermissionHolder) subject, contexts);
    }

    public static GDPermissionManager getInstance() {
        return instance;
    }

    static {
        instance = new GDPermissionManager();
    }

    @Override
    public Optional<String> getOptionValue(Option option, Set<Context> contexts) {
        return Optional.empty();
    }

    @Override
    public Optional<String> getOptionValue(Subject subject, Option option, Set<Context> contexts) {
        return Optional.empty();
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, OfflinePlayer player, Option<T> option) {
        return getInternalOptionValue(type, player, option, (ClaimType) null);
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, OfflinePlayer player, Option<T> option, Claim claim) {
        final GDPermissionHolder holder = PermissionHolderCache.getInstance().getOrCreateHolder(player.getUniqueId().toString());
        if (claim != null) {
            return this.getInternalOptionValue(type, holder, option, claim, claim.getType(), new HashSet<>());
        }
        return this.getInternalOptionValue(type, holder, option, (ClaimType) null);
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, OfflinePlayer player, Option<T> option, ClaimType claimType) {
        final GDPermissionHolder holder = PermissionHolderCache.getInstance().getOrCreateHolder(player.getUniqueId().toString());
        return this.getInternalOptionValue(type, holder, option, null, claimType, new HashSet<>());
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, GDPermissionHolder holder, Option<T> option) {
        return this.getInternalOptionValue(type, holder, option, (ClaimType) null);
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, GDPermissionHolder holder, Option<T> option, Claim claim) {
        if (claim != null) {
            return this.getInternalOptionValue(type, holder, option, claim, claim.getType(), new HashSet<>());
        }
        return this.getInternalOptionValue(type, holder, option, (ClaimType) null);
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, GDPermissionHolder holder, Option<T> option, Set<Context> contexts) {
        return getInternalOptionValue(type, holder, option, null, null, contexts);
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, GDPermissionHolder holder, Option<T> option, Claim claim, Set<Context> contexts) {
        return getInternalOptionValue(type, holder, option, claim, null, contexts);
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, GDPermissionHolder holder, Option<T> option, ClaimType claimType) {
        return this.getInternalOptionValue(type, holder, option, null, claimType, new HashSet<>());
    }

    public <T> T getInternalOptionValue(TypeToken<T> type, GDPermissionHolder holder, Option<T> option, Claim claim, ClaimType claimType, Set<Context> contexts) {
        if (holder != GriefDefenderPlugin.DEFAULT_HOLDER && holder instanceof GDPermissionUser) {
            final GDPermissionUser user = (GDPermissionUser) holder;
            final GDPlayerData playerData = (GDPlayerData) user.getPlayerData();
            if (playerData != null) {
                playerData.ignoreActiveContexts = true;
            }
            //contexts.addAll(PermissionUtil.getInstance().getActiveContexts(holder));
            PermissionUtil.getInstance().addActiveContexts(contexts, holder, playerData, claim);
        }

        Set<Context> optionContexts = new HashSet<>(contexts);
        if (!option.isGlobal() && (claim != null || claimType != null)) {
            // check claim
            if (claim != null) {
                // check override
                if (claim.isAdminClaim()) {
                    optionContexts.add(ClaimContexts.ADMIN_OVERRIDE_CONTEXT);
                } else if (claim.isTown()) {
                    optionContexts.add(ClaimContexts.TOWN_OVERRIDE_CONTEXT);
                } else if (claim.isBasicClaim()) {
                    optionContexts.add(ClaimContexts.BASIC_OVERRIDE_CONTEXT);
                } else if (claim.isWilderness()) {
                    optionContexts.add(ClaimContexts.WILDERNESS_OVERRIDE_CONTEXT);
                }
                if (!claim.isWilderness()) {
                    optionContexts.add(ClaimContexts.USER_OVERRIDE_CONTEXT);
                }

                optionContexts.add(((GDClaim) claim).getWorldContext());
                optionContexts.add(ClaimContexts.GLOBAL_OVERRIDE_CONTEXT);

                T value = this.getOptionActualValue(type, holder, option, optionContexts);
                if (value != null) {
                    return value;
                }


                // check claim owner override
                optionContexts = new HashSet<>(contexts);
                optionContexts.add(((GDClaim) claim).getWorldContext());
                optionContexts.add(claim.getOverrideClaimContext());
                value = this.getOptionActualValue(type, holder, option, optionContexts);
                if (value != null) {
                    return value;
                }

                optionContexts = new HashSet<>(contexts);
                optionContexts.add(claim.getContext());
                value = this.getOptionActualValue(type, holder, option, optionContexts);
                if (value != null) {
                    return value;
                }
            }

            // check claim type
            if (claimType != null) {
                optionContexts = new HashSet<>(contexts);
                optionContexts.add(claimType.getContext());
                final T value = this.getOptionActualValue(type, holder, option, optionContexts);
                if (value != null) {
                    return value;
                }
            }
        }

        optionContexts = new HashSet<>(contexts);
        // Check only active contexts
        T value = this.getOptionActualValue(type, holder, option, optionContexts);
        if (value != null) {
            return value;
        }

        if (claim != null) {
            optionContexts.add(claim.getDefaultTypeContext());
            value = this.getOptionActualValue(type, holder, option, optionContexts);
            if (value != null) {
                return value;
            }
            optionContexts.remove(claim.getDefaultTypeContext());
        }

        optionContexts.add(ClaimContexts.GLOBAL_DEFAULT_CONTEXT);
        if (claim != null && !claim.isWilderness()) {
            optionContexts.add(ClaimContexts.USER_DEFAULT_CONTEXT);
        }
        value = this.getOptionActualValue(type, holder, option, optionContexts);
        if (value != null) {
            return value;
        }

        // Check default holder
        if (holder != GriefDefenderPlugin.DEFAULT_HOLDER) {
            return getInternalOptionValue(type, GriefDefenderPlugin.DEFAULT_HOLDER, option, claim, claimType, contexts);
        }

        return option.getDefaultValue();
    }

    private <T> T getOptionActualValue(TypeToken<T> type, GDPermissionHolder holder, Option option, Set<Context> contexts) {
        if (option.multiValued()) {
            List<String> values = PermissionUtil.getInstance().getOptionValueList(holder, option, contexts);
            if (values != null && !values.isEmpty()) {
                return (T) values;
            }
        }
        String value = PermissionUtil.getInstance().getOptionValue(holder, option, contexts);
        if (value != null) {
            return this.getOptionTypeValue(type, value);
        }

        return null;
    }

    private <T> T getOptionTypeValue(TypeToken<T> type, String value) {
        if (type.getRawType().isAssignableFrom(Double.class)) {
            return (T) Double.valueOf(value);
        }
        if (type.getRawType().isAssignableFrom(Integer.class)) {
            if (value.equalsIgnoreCase("undefined")) {
                return (T) Integer.valueOf(-1);
            }
            Integer val  = null;
            try {
                val = Integer.valueOf(value);
            } catch (NumberFormatException e) {
                return (T) Integer.valueOf(-1);
            }
            return (T) Integer.valueOf(value);
        }
        if (type.getRawType().isAssignableFrom(String.class)) {
            return (T) value;
        }
        if (type.getRawType().isAssignableFrom(Tristate.class)) {
            if (value.equalsIgnoreCase("true")) {
                return (T) Tristate.TRUE;
            }
            if (value.equalsIgnoreCase("false")) {
                return (T) Tristate.FALSE;
            }
            int permValue = 0;
            try {
                permValue = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                
            }
            if (permValue == 0) {
                return (T) Tristate.UNDEFINED;
            }
            return (T) (permValue == 1 ? Tristate.TRUE : Tristate.FALSE);
        }
        if (type.getRawType().isAssignableFrom(CreateModeType.class)) {
            if (value.equalsIgnoreCase("undefined")) {
                return (T) CreateModeTypes.AREA;
            }
            if (value.equalsIgnoreCase("volume")) {
                return (T) CreateModeTypes.VOLUME;
            }
            if (value.equalsIgnoreCase("area")) {
                return (T) CreateModeTypes.AREA;
            }

            int permValue = 0;
            try {
                permValue = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                
            }
            if (permValue == 0) {
                return (T) CreateModeTypes.AREA;
            }
            return (T) (permValue == 1 ? CreateModeTypes.VOLUME : CreateModeTypes.AREA);
        }
        if (type.getRawType().isAssignableFrom(WeatherType.class)) {
            if (value.equalsIgnoreCase("downfall")) {
                return (T) WeatherTypes.DOWNFALL;
            }
            if (value.equalsIgnoreCase("clear")) {
                return (T) WeatherTypes.CLEAR;
            }

            return (T) WeatherTypes.UNDEFINED;
        }
        if (type.getRawType().isAssignableFrom(GameModeType.class)) {
            if (value.equalsIgnoreCase("adventure")) {
                return (T) GameModeTypes.ADVENTURE;
            }
            if (value.equalsIgnoreCase("creative")) {
                return (T) GameModeTypes.CREATIVE;
            }
            if (value.equalsIgnoreCase("spectator")) {
                return (T) GameModeTypes.SPECTATOR;
            }
            if (value.equalsIgnoreCase("survival")) {
                return (T) GameModeTypes.SURVIVAL;
            }

            return (T) GameModeTypes.UNDEFINED;
        }
        if (type.getRawType().isAssignableFrom(Boolean.class)) {
            return (T) Boolean.valueOf(Boolean.parseBoolean(value));
        }
        return (T) value;
    }

    // Uses passed contexts and only adds active contexts
    public Double getActualOptionValue(GDPermissionHolder holder, Option option, Claim claim, GDPlayerData playerData, Set<Context> contexts) {
        if (holder != GriefDefenderPlugin.DEFAULT_HOLDER) {
            if (playerData != null) {
                playerData.ignoreActiveContexts = true;
            }
            PermissionUtil.getInstance().addActiveContexts(contexts, holder, playerData, claim);
        }

        final String value = PermissionUtil.getInstance().getOptionValue(holder, option, contexts);
        if (value != null) {
            return this.getDoubleValue(value);
        }

        return Double.valueOf(option.getDefaultValue().toString());
    }

    private Double getDoubleValue(String option) {
        if (option == null) {
            return null;
        }

        double optionValue = 0.0;
        try {
            optionValue = Double.parseDouble(option);
        } catch (NumberFormatException e) {

        }
        return optionValue;
    }

    public Optional<Flag> getFlag(String value) {
        if (value == null) {
            return Optional.empty();
        }

        value = value.replace("griefdefender.flag.", "");
        String[] parts = value.split("\\.");
        if (parts.length > 0) {
            value = parts[0];
        }

        return FlagRegistryModule.getInstance().getById(value);
    }

    public Optional<Option> getOption(String value) {
        if (value == null) {
            return Optional.empty();
        }

        value = value.replace("griefdefender.", "");
        String[] parts = value.split("\\.");
        if (parts.length > 0) {
            value = parts[0];
        }

        return GriefDefender.getRegistry().getType(Option.class, value);
    }

    public Component getEventMessage() {
        return this.eventMessage;
    }

    @Override
    public CompletableFuture<PermissionResult> clearOptions() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<PermissionResult> clearOptions(Set<Context> contexts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Tristate getFlagPermissionValue(Flag flag, Subject subject, Set<Context> contexts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<PermissionResult> setFlagPermission(Flag flag, Subject subject, Tristate value,
            Set<Context> contexts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<PermissionResult> setOption(Option option, String value, Set<Context> contexts) {
        final PermissionResult result = PermissionUtil.getInstance().setOptionValue(GriefDefenderPlugin.DEFAULT_HOLDER, option.getPermission(), value, contexts);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<PermissionResult> setOption(Option option, Subject subject, String value, Set<Context> contexts) {
        final PermissionResult result = PermissionUtil.getInstance().setOptionValue((GDPermissionHolder) subject, option.getPermission(), value, contexts);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public <T> Optional<T> getOptionValue(TypeToken<T> type, Option<T> option, Set<Context> contexts) {
        String value = PermissionUtil.getInstance().getOptionValue(GriefDefenderPlugin.DEFAULT_HOLDER, option, contexts);
        if (value != null) {
            return Optional.of(this.getOptionTypeValue(type, value));
        }

        return Optional.empty();
    }

    @Override
    public <T> Optional<T> getOptionValue(TypeToken<T> type, Subject subject, Option<T> option, Set<Context> contexts) {
        String value = PermissionUtil.getInstance().getOptionValue((GDPermissionHolder) subject, option, contexts);
        if (value != null) {
            return Optional.of(this.getOptionTypeValue(type, value));
        }

        return Optional.empty();
    }

    @Override
    public <T> T getActiveOptionValue(TypeToken<T> type, Option<T> option, Subject subject, Claim claim,
            Set<Context> contexts) {
        return this.getInternalOptionValue(type, (GDPermissionHolder) subject, option, claim, claim.getType(), contexts);
    }

    @Override
    public CompletableFuture<PermissionResult> setFlagDefinition(Subject subject, FlagDefinition flagDefinition, Tristate value) {
        final Set<Context> contexts = new HashSet<>();
        contexts.addAll(flagDefinition.getContexts());
        PermissionResult result = null;
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        for (FlagData flagData : flagDefinition.getFlagData()) {
            final Set<Context> flagContexts = new HashSet<>(contexts);
            flagContexts.addAll(flagData.getContexts());
            result = PermissionUtil.getInstance().setPermissionValue((GDPermissionHolder) subject, flagData.getFlag(), value, flagContexts);
            if (!result.successful()) {
                future.complete(result);
                return future;
            }
        }

        future.complete(result);
        return future;
    }
}
