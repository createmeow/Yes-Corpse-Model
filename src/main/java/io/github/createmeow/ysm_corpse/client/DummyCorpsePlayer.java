package io.github.createmeow.ysm_corpse.client;

import com.mojang.authlib.GameProfile;
import io.github.createmeow.ysm_corpse.PlayerCorpseSnapshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * 由快照构造的隐形假人玩家：装备、皮肤部件开关、朝向与快照一致，
 * 名称标签永远隐藏，不产生任何自身动画。交给 YSM/原版玩家渲染器
 * 代理绘制。
 */
public final class DummyCorpsePlayer extends RemotePlayer {
    private static final PlayerTeam HIDDEN_NAME_TEAM = createHiddenNameTeam();

    private final PlayerCorpseSnapshot snapshot;

    public DummyCorpsePlayer(ClientLevel level, GameProfile profile,
                             NonNullList<ItemStack> equipment, PlayerCorpseSnapshot snapshot) {
        super(level, profile);
        this.snapshot = snapshot;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                setItemSlot(slot, ItemStack.EMPTY);
            } else if (slot.ordinal() < equipment.size()) {
                setItemSlot(slot, equipment.get(slot.ordinal()).copy());
            }
        }
        setPose(Pose.STANDING);
        // 舍去玩家死亡时的朝向：让 dummy 所有 yaw 字段归零，
        // 模型统一朝固定方向仰躺，避免 yaw 影响躺卧姿态
        setYRot(0.0F);
        setXRot(0.0F);
        yRotO = 0.0F;
        xRotO = 0.0F;
        yHeadRot = 0.0F;
        yHeadRotO = 0.0F;
        yBodyRot = 0.0F;
        yBodyRotO = 0.0F;
        setPos(0.0D, 0.0D, 0.0D);
        setDeltaMovement(Vec3.ZERO);
        // 注意：不能 setInvisible(true)。YSM/原版渲染器对隐形实体按全透明处理，
        // 会导致绑定了 YSM 模型也什么都画不出来（1.20.1 参考实现同样未设隐形）
        setSilent(true);
        setNoGravity(true);
        noCulling = true;
        setCustomNameVisible(false);
        setAbsorptionAmount(0.0F);
        clearHurtState();
    }

    public PlayerCorpseSnapshot snapshot() {
        return snapshot;
    }

    /** 渲染前清空受击/死亡状态，避免红色受击覆盖层。 */
    public void clearHurtState() {
        hurtTime = 0;
        hurtDuration = 0;
        deathTime = 0;
    }

    @Override
    public void tick() {
        // 假人不做任何自身更新
    }

    @Override
    public boolean isModelPartShown(PlayerModelPart part) {
        return snapshot.shows(part);
    }

    @Override
    public PlayerTeam getTeam() {
        return HIDDEN_NAME_TEAM;
    }

    private static PlayerTeam createHiddenNameTeam() {
        PlayerTeam team = new PlayerTeam(new Scoreboard(), "ysm_corpse_hidden_name");
        team.setNameTagVisibility(Team.Visibility.NEVER);
        return team;
    }
}
