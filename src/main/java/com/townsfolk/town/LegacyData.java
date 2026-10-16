package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.townsfolk.Townsfolk;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Cleans up data left on entities by features that have been removed (villager jobs, the Farming
 * order, daily trade rerolls and per-player standing), so existing worlds load cleanly.
 * <p>
 * Villagers that rolled trades for per-player standing hold trades for all five levels; they are
 * trimmed back to the trades of the villager's own level, as vanilla would have them.
 */
public final class LegacyData {
	/** Saved data of these is read as-is, only to be removed. */
	private static final List<AttachmentType<Dynamic<?>>> REMOVED = List.of(
		legacy("farm_anchor"), legacy("starting_food_given"), legacy("shepherd_pen"), legacy("flock_owner"),
		legacy("standing"), legacy("last_roll_day")
	);
	/** The level each of a villager's trades unlocked at, under per-player standing. */
	private static final AttachmentType<List<Integer>> OFFER_TIERS = AttachmentRegistry.<List<Integer>>builder()
		.persistent(Codec.INT.listOf())
		.buildAndRegister(Townsfolk.id("offer_tiers"));

	private LegacyData() {
	}

	private static AttachmentType<Dynamic<?>> legacy(String name) {
		return AttachmentRegistry.<Dynamic<?>>builder()
			.persistent(Codec.PASSTHROUGH)
			.buildAndRegister(Townsfolk.id(name));
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> clean(entity));
	}

	private static void clean(Entity entity) {
		for (AttachmentType<Dynamic<?>> type : REMOVED) {
			if (entity.hasAttached(type)) {
				entity.removeAttached(type);
			}
		}
		List<Integer> tiers = entity.getAttached(OFFER_TIERS);
		if (tiers == null) {
			return;
		}
		entity.removeAttached(OFFER_TIERS);
		if (entity instanceof Villager villager) {
			int level = villager.getVillagerData().level();
			MerchantOffers offers = villager.getOffers();
			MerchantOffers kept = new MerchantOffers();
			for (int i = 0; i < offers.size(); i++) {
				if (i >= tiers.size() || tiers.get(i) <= level) {
					kept.add(offers.get(i));
				}
			}
			villager.setOffers(kept);
		}
	}
}
