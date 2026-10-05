package io.github.createmeow.ysm_corpse;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 玩家死亡瞬间的外观快照：皮肤部件开关、姿态、朝向、装备，
 * 以及当时 YSM 附件里的 model_id / select_texture。
 * <p>
 * 快照随视觉实体通过网络同步到客户端，并持久化在尸体实体的
 * persistent data 中（旧版本数据可直接沿用）。
 */
public final class PlayerCorpseSnapshot {
    public static final int FORMAT = 4;
    public static final String TAG = "ysm_corpse_snapshot";

    private static final int EQUIPMENT_SIZE = 6;

    private final UUID playerUuid;
    private final String playerName;
    private final int modelParts;
    private final String pose;
    private final float yRot;
    private final float xRot;
    private final String ysmModel;
    private final String ysmTexture;
    private final NonNullList<ItemStack> equipment;

    private PlayerCorpseSnapshot(UUID playerUuid, String playerName, int modelParts, String pose,
                                 float yRot, float xRot, String ysmModel, String ysmTexture,
                                 NonNullList<ItemStack> equipment) {
        this.playerUuid = playerUuid;
        this.playerName = playerName == null ? "" : playerName;
        this.modelParts = modelParts;
        this.pose = pose == null ? Pose.STANDING.name() : pose;
        this.yRot = yRot;
        this.xRot = xRot;
        this.ysmModel = ysmModel == null ? "" : ysmModel;
        this.ysmTexture = ysmTexture == null ? "" : ysmTexture;
        this.equipment = copyEquipment(equipment);
    }

    /** 在玩家死亡时抓取快照。 */
    public static PlayerCorpseSnapshot capture(Player player) {
        int parts = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (player.isModelPartShown(part)) {
                parts |= part.getMask();
            }
        }
        NonNullList<ItemStack> equipment = NonNullList.withSize(EQUIPMENT_SIZE, ItemStack.EMPTY);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            // 双手物品在玩家渲染流程里另行处理，这里只保留盔甲槽位
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                continue;
            }
            int index = slot.ordinal();
            if (index < equipment.size()) {
                equipment.set(index, player.getItemBySlot(slot).copy());
            }
        }
        CompoundTag ysm = YsmAttachmentBridge.readAppearanceTag(player, player.level().registryAccess());
        YsmAttachmentBridge.Appearance appearance = YsmAttachmentBridge.appearanceFromTag(ysm);
        String source = "server-attachment";
        if (!appearance.valid()) {
            // YSM 同步附件未序列化，重进存档后服务端侧为空。
            // 单人模式下客户端/服务端共享 JVM，退回客户端每 tick 缓存的外观
            // （渲染状态模型 ID / 客户端同步附件），最后才是活体模型缓存
            YsmAttachmentBridge.Appearance client = YsmAttachmentBridge.clientAppearance(player.getUUID());
            if (client.valid()) {
                appearance = client;
                source = "client-tick-cache";
            } else {
                String clientModel = YsmAttachmentBridge.cachedClientModelId(player.getUUID());
                if (!clientModel.isBlank()) {
                    appearance = new YsmAttachmentBridge.Appearance(clientModel, "");
                    source = "client-live-cache";
                }
            }
        }
        if (DebugLog.isEnabled()) {
            org.slf4j.LoggerFactory.getLogger(PlayerCorpseSnapshot.class)
                    .info("[ysm_corpse] snapshot captured for {}: model='{}' texture='{}' (tagSize={} syncTypeKnown={} source={})",
                            player.getGameProfile().getName(), appearance.modelId(), appearance.textureId(),
                            ysm.size(), YsmAttachmentBridge.hasSyncTypeCandidate(), source);
        }
        return new PlayerCorpseSnapshot(player.getUUID(), player.getGameProfile().getName(), parts,
                player.getPose().name(), player.getYRot(), player.getXRot(),
                appearance.modelId(), appearance.textureId(), equipment);
    }

    /** 从 NBT 还原快照（客户端同步数据或尸体持久化数据）。 */
    public static PlayerCorpseSnapshot fromTag(CompoundTag tag, HolderLookup.Provider provider) {
        if (tag == null || tag.isEmpty() || !tag.contains("format")) {
            return null;
        }
        UUID uuid = tag.hasUUID("player_uuid") ? tag.getUUID("player_uuid") : new UUID(0L, 0L);
        NonNullList<ItemStack> equipment = NonNullList.withSize(EQUIPMENT_SIZE, ItemStack.EMPTY);
        if (tag.contains("equipment", Tag.TAG_LIST)) {
            ListTag list = tag.getList("equipment", Tag.TAG_COMPOUND);
            int count = Math.min(list.size(), equipment.size());
            for (int index = 0; index < count; index++) {
                // parseOptional 对空复合标签直接返回空物品堆，不产生错误日志
                equipment.set(index, ItemStack.parseOptional(provider, list.getCompound(index)));
            }
        }
        return new PlayerCorpseSnapshot(uuid, tag.getString("player_name"), tag.getInt("model_parts"),
                tag.getString("pose"), tag.getFloat("y_rot"), tag.getFloat("x_rot"),
                tag.getString("ysm_model"), tag.getString("ysm_texture"), equipment);
    }

    /** 没有死亡快照时，从尸体实体本身重建一份（含已有 YSM 数据兜底）。 */
    public static PlayerCorpseSnapshot fromCorpse(Entity corpse) {
        UUID uuid = CorpseEntityAccess.playerUuid(corpse).orElse(null);
        String name = CorpseEntityAccess.playerName(corpse);
        if (uuid == null || name.isEmpty()) {
            return null;
        }
        CompoundTag previous = corpse.getPersistentData().getCompound(TAG);
        YsmAttachmentBridge.Appearance appearance = YsmAttachmentBridge.appearanceFromTag(previous);
        return new PlayerCorpseSnapshot(uuid, name, CorpseEntityAccess.modelIndex(corpse) & 0xFF,
                Pose.STANDING.name(), corpse.getYRot(), 0.0F,
                appearance.modelId(), appearance.textureId(),
                CorpseEntityAccess.equipment(corpse));
    }

    public CompoundTag toTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", FORMAT);
        tag.putUUID("player_uuid", playerUuid);
        tag.putString("player_name", playerName);
        tag.putInt("model_parts", modelParts);
        tag.putString("pose", pose);
        tag.putFloat("y_rot", yRot);
        tag.putFloat("x_rot", xRot);
        tag.putString("ysm_model", ysmModel);
        tag.putString("ysm_texture", ysmTexture);
        ListTag list = new ListTag();
        for (ItemStack stack : equipment) {
            // 空物品堆不能用 ItemStack.save（会抛异常），记为空复合标签
            list.add(stack.isEmpty() ? new CompoundTag() : stack.save(provider));
        }
        tag.put("equipment", list);
        return tag;
    }

    /** 把快照写进尸体的 persistent data，供再次加载与客户端兜底读取。 */
    public void attachTo(Entity corpse) {
        corpse.getPersistentData().put(TAG, toTag(corpse.level().registryAccess()));
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public float yRot() {
        return yRot;
    }

    public NonNullList<ItemStack> equipment() {
        return copyEquipment(equipment);
    }

    public boolean shows(PlayerModelPart part) {
        return (modelParts & part.getMask()) == part.getMask();
    }

    public Pose poseOrStanding() {
        try {
            return Pose.valueOf(pose);
        } catch (IllegalArgumentException e) {
            return Pose.STANDING;
        }
    }

    public String ysmModel() {
        return ysmModel;
    }

    public String ysmTexture() {
        return ysmTexture;
    }

    private static NonNullList<ItemStack> copyEquipment(NonNullList<ItemStack> source) {
        NonNullList<ItemStack> copy = NonNullList.withSize(EQUIPMENT_SIZE, ItemStack.EMPTY);
        if (source == null) {
            return copy;
        }
        for (int index = 0; index < Math.min(source.size(), copy.size()); index++) {
            copy.set(index, source.get(index).copy());
        }
        return copy;
    }
}
