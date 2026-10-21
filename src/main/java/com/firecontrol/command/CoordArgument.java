package com.firecontrol.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;

// an integer, ~ or ~offset
public final class CoordArgument implements ArgumentType<String> {
	private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(Component.literal("Expected a number, ~ or ~<offset>"));

	public static CoordArgument coord() {
		return new CoordArgument();
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		int start = reader.getCursor();
		while (reader.canRead() && reader.peek() != ' ') reader.skip();
		String s = reader.getString().substring(start, reader.getCursor());
		if (!s.matches("~?-?\\d*(\\.\\d+)?") || s.isEmpty() || s.equals("-") || s.equals("~-")) {
			reader.setCursor(start);
			throw INVALID.createWithContext(reader);
		}
		return s;
	}

	public static int resolve(String s, double playerCoord) {
		if (s.startsWith("~")) {
			String rest = s.substring(1);
			double off = rest.isEmpty() ? 0 : Double.parseDouble(rest);
			return (int) Math.floor(playerCoord + off);
		}
		return (int) Math.floor(Double.parseDouble(s));
	}

	public static String get(CommandContext<?> ctx, String name) {
		return ctx.getArgument(name, String.class);
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		if (builder.getRemaining().isEmpty()) builder.suggest("~");
		return builder.buildFuture();
	}

	@Override
	public List<String> getExamples() {
		return List.of("120", "-3000", "~", "~10");
	}
}
