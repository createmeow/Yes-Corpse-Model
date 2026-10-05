package io.github.createmeow.ysm_corpse;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 世界级持久数据：记录"待删除"的视觉实体 UUID。
 * <p>
 * 尸体被丢弃时，其视觉实体可能尚未加载（在别的区块）。把待删除
 * UUID 记入该 SavedData，等视觉实体进入世界 tick 时自行销毁，
 * 避免残留幽灵尸体。
 */
public final class CorpseVisualLink extends SavedData {
    private static final String DATA_NAME = "ysm_corpse_pending_visual_removals";
    private static final String PENDING_TAG = "pending";
    private static final String UUID_TAG = "uuid";

    private final Set<UUID> pendingRemovals = new HashSet<>();

    private static CorpseVisualLink load(CompoundTag tag, HolderLookup.Provider provider) {
        CorpseVisualLink data = new CorpseVisualLink();
        ListTag pending = tag.getList(PENDING_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < pending.size(); index++) {
            CompoundTag entry = pending.getCompound(index);
            if (entry.hasUUID(UUID_TAG)) {
                data.pendingRemovals.add(entry.getUUID(UUID_TAG));
            }
        }
        return data;
    }

    public static CorpseVisualLink get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(CorpseVisualLink::new, CorpseVisualLink::load), DATA_NAME);
    }

    public void scheduleRemoval(UUID visualUuid) {
        if (pendingRemovals.add(visualUuid)) {
            setDirty();
        }
    }

    public boolean shouldRemove(UUID visualUuid) {
        return pendingRemovals.contains(visualUuid);
    }

    public boolean consumeRemoval(UUID visualUuid) {
        if (!pendingRemovals.remove(visualUuid)) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag pending = new ListTag();
        for (UUID uuid : pendingRemovals) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(UUID_TAG, uuid);
            pending.add(entry);
        }
        tag.put(PENDING_TAG, pending);
        return tag;
    }
}
