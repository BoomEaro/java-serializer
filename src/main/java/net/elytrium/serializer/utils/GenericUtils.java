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

package net.elytrium.serializer.utils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class GenericUtils {

  public static Class<?> unwrapClassParameterizedType(Type type) {
    if (type instanceof ParameterizedType parameterizedType) {
      if (parameterizedType.getRawType() instanceof Class<?> clazz) {
        return clazz;
      } else {
        throw new IllegalArgumentException("Can only unwrap Class<?> from ParameterizedType");
      }
    } else if (type instanceof Class<?> clazz) {
      return clazz;
    } else {
      throw new IllegalArgumentException("Can only unwrap Class<?> or ParameterizedType of Class<?>");
    }
  }

  public static Type getParameterTypeFromSuperclass(Class<?> parent, Type type, Type superclass, int searchIndex) {
    Map<TypeVariable<?>, Type> bindings = GenericUtils.bindings(type, Map.of());
    return GenericUtils.findParameter(parent, superclass, searchIndex, bindings);
  }

  public static Type getParameterType(Class<?> parent, Type type, int index) {
    Type parameterType = GenericUtils.findParameter(parent, type, index, Map.of());
    return parameterType == null ? Object.class : parameterType;
  }

  private static Type findParameter(Class<?> parent, Type type, int index, Map<TypeVariable<?>, Type> inherited) {
    if (type == null) {
      return null;
    }

    Class<?> clazz = GenericUtils.unwrapClassParameterizedType(type);
    if (!parent.isAssignableFrom(clazz)) {
      return null;
    }

    Map<TypeVariable<?>, Type> bindings = GenericUtils.bindings(type, inherited);
    if (clazz == parent) {
      return GenericUtils.resolve(clazz.getTypeParameters()[index], bindings, new HashSet<>());
    }

    Type result = GenericUtils.findParameter(parent, clazz.getGenericSuperclass(), index, bindings);
    if (result != null) {
      return result;
    }

    for (Type genericInterface : clazz.getGenericInterfaces()) {
      result = GenericUtils.findParameter(parent, genericInterface, index, bindings);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  private static Map<TypeVariable<?>, Type> bindings(Type type, Map<TypeVariable<?>, Type> inherited) {
    Map<TypeVariable<?>, Type> result = new HashMap<>(inherited);
    if (type instanceof ParameterizedType parameterizedType) {
      TypeVariable<?>[] variables = GenericUtils.unwrapClassParameterizedType(type).getTypeParameters();
      Type[] arguments = parameterizedType.getActualTypeArguments();
      for (int i = 0; i < variables.length; ++i) {
        result.put(variables[i], GenericUtils.resolve(arguments[i], inherited, new HashSet<>()));
      }
    }
    return result;
  }

  private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings, Set<TypeVariable<?>> visited) {
    if (type instanceof TypeVariable<?> variable) {
      if (!visited.add(variable)) {
        return Object.class;
      }
      Type replacement = bindings.get(variable);
      if (replacement == null || replacement.equals(variable)) {
        replacement = variable.getBounds()[0];
      }
      Type result = GenericUtils.resolve(replacement, bindings, visited);
      visited.remove(variable);
      return result;
    } else if (type instanceof ParameterizedType parameterizedType) {
      Type[] arguments = parameterizedType.getActualTypeArguments();
      for (int i = 0; i < arguments.length; ++i) {
        arguments[i] = GenericUtils.resolve(arguments[i], bindings, visited);
      }
      Type owner = parameterizedType.getOwnerType();
      return new ResolvedParameterizedType(parameterizedType.getRawType(), owner == null ? null : GenericUtils.resolve(owner, bindings, visited), arguments);
    } else if (type instanceof WildcardType wildcardType) {
      return GenericUtils.resolve(wildcardType.getUpperBounds()[0], bindings, visited);
    }
    return type;
  }

  private static final class ResolvedParameterizedType implements ParameterizedType {

    private final Type rawType;
    private final Type ownerType;
    private final Type[] arguments;

    private ResolvedParameterizedType(Type rawType, Type ownerType, Type[] arguments) {
      this.rawType = rawType;
      this.ownerType = ownerType;
      this.arguments = arguments.clone();
    }

    @Override
    public Type[] getActualTypeArguments() {
      return this.arguments.clone();
    }

    @Override
    public Type getRawType() {
      return this.rawType;
    }

    @Override
    public Type getOwnerType() {
      return this.ownerType;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ParameterizedType type && this.rawType.equals(type.getRawType())
          && Objects.equals(this.ownerType, type.getOwnerType()) && Arrays.equals(this.arguments, type.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
      return this.rawType.hashCode() ^ Objects.hashCode(this.ownerType) ^ Arrays.hashCode(this.arguments);
    }

    @Override
    public String getTypeName() {
      return this.rawType.getTypeName() + "<" + String.join(", ", Arrays.stream(this.arguments).map(Type::getTypeName).toList()) + ">";
    }

    @Override
    public String toString() {
      return this.getTypeName();
    }
  }

  public static int getParameterIndex(TypeVariable<?>[] variables, TypeVariable<?> expected) {
    for (int i = 0; i < variables.length; ++i) {
      if (expected.equals(variables[i])) {
        return i;
      }
    }

    return -1;
  }
}
