package net.javadiscord.javabot.api.gson;

import com.google.gson.*;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.lang.reflect.Type;

/**
 * Adapter class for {@link Gson} which configures the serialization and deserialization of the {@link Color} class.
 */
public class GsonColorAdapter implements JsonSerializer<Color>, JsonDeserializer<Color> {

	@Override
	public Color deserialize(@NotNull JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
		return Color.decode(jsonElement.getAsString());
	}

	@Override
	public JsonPrimitive serialize(@NotNull Color color, Type type, JsonSerializationContext jsonSerializationContext) {
		return new JsonPrimitive("#" + Integer.toHexString(color.getRGB()).substring(2).toUpperCase());
	}
}
