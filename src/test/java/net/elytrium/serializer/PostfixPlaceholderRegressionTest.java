package net.elytrium.serializer;

import java.util.List;
import net.elytrium.serializer.placeholders.DefaultPlaceholderReplacer;
import net.elytrium.serializer.placeholders.Placeholders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PostfixPlaceholderRegressionTest {

  @Test
  void objectHolderSuppliesMetadataForCollectionValue() {
    Object holder = new Object();
    Placeholders.addPlaceholders(holder, new DefaultPlaceholderReplacer(), "name");
    try {
      List<String> result = Placeholders.replaceFor(holder, List.of("value {name}", "next {name}"), "Alice");
      Assertions.assertEquals(List.of("value Alice", "next Alice"), result);
    } finally {
      Placeholders.removePlaceholders(holder);
    }
  }

  @Test
  void collectionHolderDoesNotReplaceTheSuppliedCollectionValue() {
    List<String> holder = List.of("holder {name}");
    List<String> value = List.of("value {name}");
    Placeholders.addPlaceholders(holder, new DefaultPlaceholderReplacer(), "name");
    try {
      List<String> result = Placeholders.replaceFor(holder, value, "Alice");
      Assertions.assertEquals(List.of("value Alice"), result);
      Assertions.assertEquals(List.of("value {name}"), value);
      Assertions.assertEquals(List.of("holder {name}"), holder);
    } finally {
      Placeholders.removePlaceholders(holder);
    }
  }

  @Test
  void separateHolderAlsoWorksForScalarValue() {
    Object holder = new Object();
    Placeholders.addPlaceholders(holder, new DefaultPlaceholderReplacer(), "name");
    try {
      String result = Placeholders.replaceFor(holder, "value {name}", "Alice");
      Assertions.assertEquals("value Alice", result);
    } finally {
      Placeholders.removePlaceholders(holder);
    }
  }

  @Test
  void replaceCollectionRetainsPerElementRegistrations() {
    String first = new String("first {name}");
    String second = new String("second {person}");
    Placeholders.addPlaceholders(first, new DefaultPlaceholderReplacer(), "name");
    Placeholders.addPlaceholders(second, new DefaultPlaceholderReplacer(), "person");
    try {
      List<String> result = Placeholders.replace(List.of(first, second), "Alice");
      Assertions.assertEquals(List.of("first Alice", "second Alice"), result);
    } finally {
      Placeholders.removePlaceholders(first);
      Placeholders.removePlaceholders(second);
    }
  }
}
