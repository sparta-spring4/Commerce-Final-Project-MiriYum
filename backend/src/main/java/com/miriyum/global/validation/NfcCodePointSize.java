package com.miriyum.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.text.Normalizer;

@Documented
@Constraint(validatedBy = NfcCodePointSize.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NfcCodePointSize {
    String message() default "NFC-normalized code point length is outside the allowed range";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    final class Validator implements ConstraintValidator<NfcCodePointSize, String> {
        private int min;
        private int max;

        @Override
        public void initialize(NfcCodePointSize constraint) {
            min = constraint.min();
            max = constraint.max();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
            int length = normalized.codePointCount(0, normalized.length());
            return length >= min && length <= max;
        }
    }
}
