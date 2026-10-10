/*
 * Copyright (C) 2023 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.serializer.placeholders;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.elytrium.serializer.utils.GenericUtils;

@SuppressWarnings("unchecked")
public class Placeholders {

  private static final ReferenceQueue<Object> COLLECTED_HOLDERS = new ReferenceQueue<>();
  private static final Map<IdentityReference, Placeholderable<?, ?>> PLACEHOLDERS = new HashMap<>();
  private static final Map<Integer, Placeholderable<?, ?>> LEGACY_PLACEHOLDERS = new HashMap<>();

  public static <T, R> R replace(T value, Object... values) {
    return Placeholders.replaceFor(value, value, values);
  }

  public static <H, T, P, R> R replaceFor(H holder, T value, Object... values) {
    var placeholderable = (Placeholderable<T, P>) Placeholders.lookup(holder);
    if (value instanceof Collection<?> collection) {
      List<T> result = new ArrayList<>(collection.size());
      for (Object entry : collection) {
        var entryPlaceholders = placeholderable == null ? (Placeholderable<T, P>) Placeholders.lookup(entry) : placeholderable;
        if (entryPlaceholders == null) {
          throw new IllegalStateException("Invalid input!");
        }

        result.add(entryPlaceholders.replacer.replace((T) entry, entryPlaceholders.placeholders, values));
      }

      return (R) result;
    } else if (placeholderable == null) {
      throw new IllegalStateException("Invalid input!");
    } else {
      return (R) placeholderable.replacer.replace(value, placeholderable.placeholders, values);
    }
  }

  public static void addPlaceholders(Object value, PlaceholderReplacer<?, ?> replacer, String... placeholders) {
    Placeholders.addPlaceholders(value, replacer, true, placeholders);
  }

  public static void addPlaceholders(Object value, PlaceholderReplacer<?, ?> replacer, boolean wrapWithBraces, String... placeholders) {
    Placeholders.prepareRegistration(value, replacer, wrapWithBraces, placeholders).run();
  }

  @Deprecated
  public static void addPlaceholders(int hash, PlaceholderReplacer<?, ?> replacer, String... placeholders) {
    Placeholders.addPlaceholders(hash, replacer, true, placeholders);
  }

  @Deprecated
  public static synchronized void addPlaceholders(int hash, PlaceholderReplacer<?, ?> replacer, boolean wrapWithBraces, String... placeholders) {
    Placeholders.LEGACY_PLACEHOLDERS.put(hash, new Placeholderable<>(replacer, placeholders, wrapWithBraces));
  }

  public static synchronized void setPlaceholders(Object value, PlaceholderReplacer<?, ?> fallbackReplacer, String... placeholders) {
    Placeholderable<?, ?> placeholderable = Placeholders.lookup(value);
    if (placeholderable != null) {
      placeholderable.setPlaceholders(placeholders, true);
    } else if (fallbackReplacer != null) {
      Placeholders.addPlaceholders(value, fallbackReplacer, true, placeholders);
    }
  }

  @Deprecated
  public static void setPlaceholders(int hash, PlaceholderReplacer<?, ?> fallbackReplacer, String... placeholders) {
    Placeholders.setPlaceholders(hash, fallbackReplacer, true, placeholders);
  }

  @Deprecated
  public static synchronized void setPlaceholders(int hash, PlaceholderReplacer<?, ?> fallbackReplacer, boolean wrapWithBraces, String... placeholders) {
    Placeholderable<?, ?> placeholderable = Placeholders.LEGACY_PLACEHOLDERS.get(hash);
    if (placeholderable == null) {
      if (fallbackReplacer != null) {
        Placeholders.addPlaceholders(hash, fallbackReplacer, wrapWithBraces, placeholders);
      }
    } else {
      placeholderable.setPlaceholders(placeholders, wrapWithBraces);
    }
  }

  public static synchronized void removePlaceholders(Object value) {
    Placeholders.expungeCollectedHolders();
    Placeholders.PLACEHOLDERS.remove(new IdentityReference(value, null));
    Placeholders.LEGACY_PLACEHOLDERS.remove(System.identityHashCode(value));
  }

  @Deprecated
  public static synchronized void removePlaceholders(int hash) {
    Placeholders.LEGACY_PLACEHOLDERS.remove(hash);
  }

  public static boolean hasPlaceholders(Object value) {
    return Placeholders.lookup(value) != null;
  }

  @Deprecated
  public static synchronized boolean hasPlaceholders(int hash) {
    return Placeholders.LEGACY_PLACEHOLDERS.containsKey(hash);
  }

  public static <P> P[] getPlaceholders(Object value) {
    return ((Placeholderable<?, P>) Placeholders.require(Placeholders.lookup(value))).placeholders.clone();
  }

  @Deprecated
  public static synchronized <P> P[] getPlaceholders(int hash) {
    return ((Placeholderable<?, P>) Placeholders.require(Placeholders.LEGACY_PLACEHOLDERS.get(hash))).placeholders.clone();
  }

  public static <T> PlaceholderReplacer<T, ?> getReplacer(T value) {
    return ((Placeholderable<T, ?>) Placeholders.require(Placeholders.lookup(value))).replacer;
  }

  @Deprecated
  public static synchronized <T> PlaceholderReplacer<T, ?> getReplacer(int hash) {
    return ((Placeholderable<T, ?>) Placeholders.require(Placeholders.LEGACY_PLACEHOLDERS.get(hash))).replacer;
  }

  private static synchronized Placeholderable<?, ?> lookup(Object holder) {
    Placeholders.expungeCollectedHolders();
    Placeholderable<?, ?> result = Placeholders.PLACEHOLDERS.get(new IdentityReference(holder, null));
    return result == null ? Placeholders.LEGACY_PLACEHOLDERS.get(System.identityHashCode(holder)) : result;
  }

  /**
   * Validates and transforms placeholders now, deferring only their registration until the returned action runs.
   */
  public static Runnable prepareRegistration(Object value, PlaceholderReplacer<?, ?> replacer, boolean wrapWithBraces, String... placeholders) {
    Objects.requireNonNull(value, "Placeholder holder");
    Placeholderable<?, ?> registration = new Placeholderable<>(replacer, placeholders, wrapWithBraces);
    return () -> {
      synchronized (Placeholders.class) {
        Placeholders.expungeCollectedHolders();
        Placeholders.PLACEHOLDERS.put(new IdentityReference(value, Placeholders.COLLECTED_HOLDERS), registration);
      }
    };
  }

  private static Placeholderable<?, ?> require(Placeholderable<?, ?> placeholderable) {
    if (placeholderable == null) {
      throw new IllegalStateException("Invalid input!");
    } else {
      return placeholderable;
    }
  }

  private static void expungeCollectedHolders() {
    IdentityReference reference;
    while ((reference = (IdentityReference) Placeholders.COLLECTED_HOLDERS.poll()) != null) {
      Placeholders.PLACEHOLDERS.remove(reference);
    }
  }

  private static final class IdentityReference extends WeakReference<Object> {

    private final int hash;

    private IdentityReference(Object holder, ReferenceQueue<Object> queue) {
      super(holder, queue);
      this.hash = System.identityHashCode(holder);
    }

    @Override
    public int hashCode() {
      return this.hash;
    }

    @Override
    public boolean equals(Object other) {
      Object holder = this.get();
      return this == other || other instanceof IdentityReference reference && holder != null && holder == reference.get();
    }
  }

  private static class Placeholderable<T, P> {

    private final PlaceholderReplacer<T, P> replacer;
    private final Class<P> placeholdersClass;
    private volatile P[] placeholders;

    private Placeholderable(PlaceholderReplacer<T, P> replacer, String[] placeholders, boolean wrapWithBraces) {
      this.replacer = replacer;
      this.placeholdersClass = this.determinePlaceholdersClass();
      this.setPlaceholders(placeholders, wrapWithBraces);
    }

    private Class<P> determinePlaceholdersClass() {
      return (Class<P>) GenericUtils.unwrapClassParameterizedType(GenericUtils.getParameterType(PlaceholderReplacer.class, this.replacer.getClass(), 1));
    }

    private void setPlaceholders(String[] placeholders, boolean wrapWithBraces) {
      P[] transformed = (P[]) Array.newInstance(this.placeholdersClass, placeholders.length);
      for (int i = placeholders.length - 1; i >= 0; --i) {
        String placeholder = placeholders[i];
        if (placeholder.isEmpty()) {
          throw new IllegalArgumentException("Placeholder must not be empty");
        }
        transformed[i] = this.replacer.transformPlaceholder(wrapWithBraces && (placeholder.charAt(0) != '{' || placeholder.charAt(placeholder.length() - 1) != '}')
            ? '{' + placeholder + '}'
            : placeholder
        );
      }
      this.placeholders = transformed;
    }
  }
}
