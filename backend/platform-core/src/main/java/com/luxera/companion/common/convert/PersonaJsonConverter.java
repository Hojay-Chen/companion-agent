package com.luxera.companion.common.convert;

import com.luxera.companion.common.JsonCodec;
import com.luxera.companion.persona.Persona;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

@Converter
public class PersonaJsonConverter implements AttributeConverter<Persona, String> {
    @Override
    public String convertToDatabaseColumn(Persona attribute) {
        return JsonCodec.toJson(attribute);
    }

    @Override
    public Persona convertToEntityAttribute(String dbData) {
        return JsonCodec.fromJson(dbData, Persona.class);
    }
}
