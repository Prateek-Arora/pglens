package com.pglens.server.web;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Makes the OpenAPI schemas as exact as the Java records they describe (ADR-0046): every record
 * component is <em>required</em> — Jackson always writes it — and only a component annotated
 * {@code @Nullable} may be {@code null}. Without this every field of the generated TypeScript types
 * would be optional, and the dashboard could not tell "no value" from a mistake. So: a response
 * field that can be null must carry {@code @Nullable}, or clients are told it never is.
 */
@Component
class RecordSchemaConverter implements ModelConverter {

  private static final String NULL = "null";

  @Override
  public Schema<?> resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
    if (schema == null || !raw.isRecord()) {
      return schema;
    }
    Schema<?> model =
        schema.get$ref() == null
            ? schema
            : context.getDefinedModels().get(schema.get$ref().replace("#/components/schemas/", ""));
    if (model == null || model.getProperties() == null) {
      return schema;
    }
    for (RecordComponent c : raw.getRecordComponents()) {
      Schema<?> property = model.getProperties().get(c.getName());
      if (property == null) {
        continue;
      }
      if (c.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
        model.getProperties().put(c.getName(), nullable(property));
      }
      if (model.getRequired() == null || !model.getRequired().contains(c.getName())) {
        model.addRequiredItem(c.getName());
      }
    }
    return schema;
  }

  /** The property's schema, widened to also allow {@code null} (OpenAPI 3.1 / JSON Schema). */
  private static Schema<?> nullable(Schema<?> property) {
    if (property.getAnyOf() != null) {
      return property; // already widened (models are resolved more than once)
    }
    if (property.get$ref() != null) {
      return new JsonSchema().anyOf(List.of(property, new JsonSchema().types(Set.of(NULL))));
    }
    Set<String> types = new LinkedHashSet<>();
    if (property.getTypes() != null) {
      types.addAll(property.getTypes());
    } else if (property.getType() != null) {
      types.add(property.getType());
    }
    types.add(NULL);
    property.setTypes(types);
    if (property.getEnum() != null && !property.getEnum().contains(null)) {
      List<Object> values = new ArrayList<>(property.getEnum());
      values.add(null);
      @SuppressWarnings("unchecked")
      Schema<Object> widened = (Schema<Object>) property;
      widened.setEnum(values);
    }
    return property;
  }
}
