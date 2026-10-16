package com.townsfolk.mixin;

import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The players who fought in a raid (its Heroes of the Village), for rewarding them when it is won. */
@Mixin(Raid.class)
public interface RaidAccessor {
	@Accessor("heroesOfTheVillage")
	Set<UUID> townsfolk$heroes();
}
