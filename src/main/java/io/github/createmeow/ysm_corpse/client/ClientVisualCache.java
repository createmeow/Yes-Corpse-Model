package io.github.createmeow.ysm_corpse.client;

import com.mojang.authlib.GameProfile;
import io.github.createmeow.ysm_corpse.CorpseEntityAccess;
import io.github.createmeow.ysm_corpse.CorpseVisualEntity;
import io.github.createmeow.ysm_corpse.PlayerCorpseSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端渲染缓存：按实体 id 保存快照与假人玩家。
 * 快照内容变化（如换肤数据更新）时自动重建假人。
 */
public final class ClientVisualCache {
    private static final Map<Integer, PlayerCorpseSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<Integer, DummyCorpsePlayer> DUMMIES = new ConcurrentHashMap<>();

    private ClientVisualCache() {
    }

    public static PlayerCorpseSnapshot snapshot(Entity entity) {
        PlayerCorpseSnapshot cached = SNAPSHOTS.get(entity.getId());
        if (cached != null) {
            return cached;
        }
        PlayerCorpseSnapshot snapshot = null;
        if (entity instanceof CorpseVisualEntity visual) {
            snapshot = PlayerCorpseSnapshot.fromTag(visual.snapshotTag(), entity.level().registryAccess());
        }
        if (snapshot == null && CorpseEntityAccess.isCorpse(entity)) {
            snapshot = PlayerCorpseSnapshot.fromCorpse(entity);
        }
        if (snapshot != null) {
            SNAPSHOTS.put(entity.getId(), snapshot);
        }
        return snapshot;
    }

    public static DummyCorpsePlayer dummy(Entity entity, PlayerCorpseSnapshot snapshot) {
        DummyCorpsePlayer existing = DUMMIES.get(entity.getId());
        if (existing != null) {
            return existing;
        }
        if (!(entity.level() instanceof ClientLevel level)) {
            return null;
        }
        GameProfile profile = new GameProfile(snapshot.playerUuid(), snapshot.playerName());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(snapshot.playerUuid());
            if (info != null) {
                profile = info.getProfile();
            }
        }
        DummyCorpsePlayer dummy = new DummyCorpsePlayer(level, profile, snapshot.equipment(), snapshot);
        dummy.setPos(entity.getX(), entity.getY(), entity.getZ());
        DUMMIES.put(entity.getId(), dummy);
        return dummy;
    }

    public static void forget(int entityId) {
        SNAPSHOTS.remove(entityId);
        DUMMIES.remove(entityId);
    }

    public static void clear() {
        SNAPSHOTS.clear();
        DUMMIES.clear();
    }
}
