package com.townsfolk.town;

import com.townsfolk.TownsfolkConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /town} for operators: list towns, view or change players' reputation with them, and reload
 * the settings file.
 * A town is given by name (Tab completes; quote names with spaces) or {@code nearest}.
 */
public final class TownCommand {
	private static final String NEAREST = "nearest";

	private static final DynamicCommandExceptionType UNKNOWN_TOWN = new DynamicCommandExceptionType(
		name -> Component.translatable("commands.townsfolk.town.unknown", name));
	private static final DynamicCommandExceptionType UNKNOWN_RANK = new DynamicCommandExceptionType(
		name -> Component.translatable("commands.townsfolk.rank.unknown", name));

	private static final SuggestionProvider<CommandSourceStack> TOWN_SUGGESTIONS = (context, builder) -> {
		List<String> names = new java.util.ArrayList<>();
		names.add(NEAREST);
		for (TownRegistry.Town town : TownRegistry.get(context.getSource().getLevel()).all().values()) {
			names.add(StringArgumentType.escapeIfRequired(town.name()));
		}
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private static final SuggestionProvider<CommandSourceStack> RANK_SUGGESTIONS = (context, builder) ->
		SharedSuggestionProvider.suggest(
			java.util.Arrays.stream(TownReputation.Rank.values()).map(r -> r.name().toLowerCase(Locale.ROOT)),
			builder);

	private TownCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("town")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("list").executes(TownCommand::list))
			.then(Commands.literal("reload").executes(ctx -> {
				TownsfolkConfig.load();
				ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.reload", TownsfolkConfig.file().toString()), true);
				return 1;
			}))
			.then(Commands.literal("reputation")
				.then(Commands.literal("get")
					.then(townArgument()
						.executes(ctx -> get(ctx, List.of(ctx.getSource().getPlayerOrException())))
						.then(Commands.argument("players", EntityArgument.players())
							.executes(ctx -> get(ctx, EntityArgument.getPlayers(ctx, "players"))))))
				.then(change("set", (current, amount) -> amount))
				.then(change("add", Integer::sum))
				.then(Commands.literal("rank")
					.then(townArgument()
						.then(Commands.argument("players", EntityArgument.players())
							.then(Commands.argument("rank", StringArgumentType.word())
								.suggests(RANK_SUGGESTIONS)
								.executes(TownCommand::setRank)))))));
	}

	private static ArgumentBuilder<CommandSourceStack, ?> townArgument() {
		return Commands.argument("town", StringArgumentType.string()).suggests(TOWN_SUGGESTIONS);
	}

	/** {@code set} and {@code add}: {@code <town> <players> <amount>}. */
	private static ArgumentBuilder<CommandSourceStack, ?> change(String name, IntBinaryOperator operation) {
		return Commands.literal(name)
			.then(townArgument()
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("amount", IntegerArgumentType.integer())
						.executes(ctx -> change(ctx, operation)))));
	}

	/** Resolves the town argument: a name (the nearest match if several towns share it) or "nearest". */
	private static Map.Entry<BlockPos, TownRegistry.Town> town(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String name = StringArgumentType.getString(ctx, "town");
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 from = ctx.getSource().getPosition();
		return TownRegistry.get(level).all().entrySet().stream()
			.filter(e -> name.equalsIgnoreCase(NEAREST) || e.getValue().name().equalsIgnoreCase(name))
			.min(Comparator.comparingDouble(e -> Vec3.atCenterOf(e.getKey()).distanceToSqr(from)))
			.orElseThrow(() -> UNKNOWN_TOWN.create(name));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Map<BlockPos, TownRegistry.Town> towns = TownRegistry.get(ctx.getSource().getLevel()).all();
		if (towns.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.list.none"), false);
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.list.header", towns.size()), false);
		towns.forEach((pos, town) -> ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.list.entry",
			town.name(), pos.getX(), pos.getY(), pos.getZ(), town.villagers().size(), town.golems().size(), town.players().size()), false));
		return towns.size();
	}

	private static int get(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) throws CommandSyntaxException {
		Map.Entry<BlockPos, TownRegistry.Town> town = town(ctx);
		TownReputation reputation = TownReputation.get(ctx.getSource().getLevel());
		for (ServerPlayer player : players) {
			int value = reputation.get(town.getKey(), player.getUUID());
			ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.reputation.get",
				player.getDisplayName(), town.getValue().name(), value, TownReputation.Rank.of(value).displayName()), false);
		}
		return players.size();
	}

	private static int change(CommandContext<CommandSourceStack> ctx, IntBinaryOperator operation) throws CommandSyntaxException {
		Map.Entry<BlockPos, TownRegistry.Town> town = town(ctx);
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		TownReputation reputation = TownReputation.get(ctx.getSource().getLevel());
		for (ServerPlayer player : players) {
			int value = operation.applyAsInt(reputation.get(town.getKey(), player.getUUID()), amount);
			reputation.set(town.getKey(), player.getUUID(), value);
			ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.reputation.set",
				player.getDisplayName(), town.getValue().name(), value, TownReputation.Rank.of(value).displayName()), true);
		}
		return players.size();
	}

	private static int setRank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String rankName = StringArgumentType.getString(ctx, "rank");
		TownReputation.Rank rank;
		try {
			rank = TownReputation.Rank.valueOf(rankName.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw UNKNOWN_RANK.create(rankName);
		}
		// Stranger's threshold sits just above Enemy; zero is the natural "Stranger" value.
		int value = rank == TownReputation.Rank.STRANGER ? 0 : rank.threshold;

		Map.Entry<BlockPos, TownRegistry.Town> town = town(ctx);
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
		TownReputation reputation = TownReputation.get(ctx.getSource().getLevel());
		for (ServerPlayer player : players) {
			reputation.set(town.getKey(), player.getUUID(), value);
			ctx.getSource().sendSuccess(() -> Component.translatable("commands.townsfolk.reputation.set",
				player.getDisplayName(), town.getValue().name(), value, rank.displayName()), true);
		}
		return players.size();
	}
}
