/*
 * This file is part of text, licensed under the MIT License.
 *
 * Copyright (c) 2017-2019 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.text.serializer.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.kyori.text.BuildableComponent;
import net.kyori.text.Component;
import net.kyori.text.ComponentBuilder;
import net.kyori.text.TextComponent;
import net.kyori.text.TranslatableComponent;
import net.kyori.text.event.ClickEvent;
import net.kyori.text.event.HoverEvent;
import net.kyori.text.format.Style;
import net.kyori.text.format.TextColor;
import net.kyori.text.format.TextDecoration;
import net.kyori.text.serializer.ComponentSerializer;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GsonComponentSerializer implements ComponentSerializer<Component, Component, String>, JsonDeserializer<Component>, JsonSerializer<Component> {
  /**
   * A component serializer for JSON-based serialization and deserialization.
   */
  public static final GsonComponentSerializer INSTANCE = new GsonComponentSerializer();
  static final Gson GSON = populate(new GsonBuilder()).create();

  /**
   * Populate a builder with our serializers.
   *
   * @param builder the gson builder
   * @return the gson builder
   */
  public static @NonNull GsonBuilder populate(final @NonNull GsonBuilder builder) {
    builder
      .registerTypeHierarchyAdapter(Component.class, INSTANCE)
      .registerTypeAdapter(Style.class, StyleSerializer.INSTANCE)
      .registerTypeAdapter(ClickEvent.Action.class, new NameMapSerializer<>("click action", ClickEvent.Action.NAMES))
      .registerTypeAdapter(HoverEvent.Action.class, new NameMapSerializer<>("hover action", HoverEvent.Action.NAMES))
      .registerTypeAdapter(TextColorWrapper.class, new TextColorWrapper.Serializer())
      .registerTypeAdapter(TextColor.class, new NameMapSerializer<>("text color", TextColor.NAMES))
      .registerTypeAdapter(TextDecoration.class, new NameMapSerializer<>("text decoration", TextDecoration.NAMES));
    return builder;
  }

  static final String TEXT = "text";
  static final String TRANSLATE = "translate";
  static final String TRANSLATE_WITH = "with";
  static final String EXTRA = "extra";

  @Override
  public @NonNull Component deserialize(final @NonNull String string) {
    return GSON.fromJson(string, Component.class);
  }

  @Override
  public @NonNull String serialize(final @NonNull Component component) {
    return GSON.toJson(component);
  }

  // Not part of the API.
  @Deprecated
  @Override
  public Component deserialize(final JsonElement json, final Type typeOfT, final JsonDeserializationContext context) throws JsonParseException {
    return this.deserialize0(json, context);
  }

  private BuildableComponent<?, ?> deserialize0(final JsonElement element, final JsonDeserializationContext context) throws JsonParseException {
    if(element.isJsonPrimitive()) {
      return TextComponent.of(element.getAsString());
    } else if(element.isJsonArray()) {
      ComponentBuilder<?, ?> parent = null;
      for(final JsonElement childElement : element.getAsJsonArray()) {
        final BuildableComponent<?, ?> child = this.deserialize0(childElement, context);
        if(parent == null) {
          parent = child.toBuilder();
        } else {
          parent.append(child);
        }
      }
      if(parent == null) {
        throw new JsonParseException("Don't know how to turn " + element + " into a Component");
      }
      return parent.build();
    } else if(!element.isJsonObject()) {
      throw new JsonParseException("Don't know how to turn " + element + " into a Component");
    }

    final JsonObject object = element.getAsJsonObject();
    final ComponentBuilder<?, ?> component;
    if(object.has(TEXT)) {
      component = TextComponent.builder(object.get(TEXT).getAsString());
    } else if(object.has(TRANSLATE)) {
      final String key = object.get(TRANSLATE).getAsString();
      if(!object.has(TRANSLATE_WITH)) {
        component = TranslatableComponent.builder(key);
      } else {
        final JsonArray with = object.getAsJsonArray(TRANSLATE_WITH);
        final List<Component> args = new ArrayList<>(with.size());
        for(int i = 0, size = with.size(); i < size; i++) {
          final JsonElement argElement = with.get(i);
          args.add(this.deserialize0(argElement, context));
        }
        component = TranslatableComponent.builder(key).args(args);
      }
    } else {
      throw notSureHowToDeserialize(element);
    }

    if(object.has(EXTRA)) {
      final JsonArray extra = object.getAsJsonArray(EXTRA);
      for(int i = 0, size = extra.size(); i < size; i++) {
        final JsonElement extraElement = extra.get(i);
        component.append(this.deserialize0(extraElement, context));
      }
    }

    final Style style = context.deserialize(element, Style.class);
    if(!style.isEmpty()) {
      component.style(style);
    }

    return component.build();
  }

  // Not part of the API.
  @Deprecated
  @Override
  public JsonElement serialize(final Component src, final Type typeOfSrc, final JsonSerializationContext context) {
    final JsonObject object = new JsonObject();
    if(src instanceof TextComponent) {
      object.addProperty(TEXT, ((TextComponent) src).content());
    } else if(src instanceof TranslatableComponent) {
      final TranslatableComponent tc = (TranslatableComponent) src;
      object.addProperty(TRANSLATE, tc.key());
      if(!tc.args().isEmpty()) {
        final JsonArray with = new JsonArray();
        for(final Component arg : tc.args()) {
          with.add(context.serialize(arg));
        }
        object.add(TRANSLATE_WITH, with);
      }
    } else {
      throw notSureHowToSerialize(src);
    }

    if(!src.children().isEmpty()) {
      final JsonArray extra = new JsonArray();
      for(final Component child : src.children()) {
        extra.add(context.serialize(child));
      }
      object.add(EXTRA, extra);
    }

    if(src.hasStyling()) {
      final JsonElement style = context.serialize(src.style());
      if(style.isJsonObject()) {
        for(final Map.Entry<String, JsonElement> entry : ((JsonObject) style).entrySet()) {
          object.add(entry.getKey(), entry.getValue());
        }
      }
    }

    return object;
  }

  private static JsonParseException notSureHowToDeserialize(final JsonElement element) {
    return new JsonParseException("Don't know how to turn " + element + " into a Component");
  }

  private static IllegalArgumentException notSureHowToSerialize(final Component component) {
    return new IllegalArgumentException("Don't know how to serialize " + component + " as a Component");
  }
}
