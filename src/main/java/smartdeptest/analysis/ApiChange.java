package smartdeptest.analysis;

import java.util.List;

public record ApiChange(Kind kind, String className, String memberName,
                        String oldSignature, String newSignature,
                        List<String> oldParameterTypes, boolean potentiallyIncompatible) {
    public enum Kind {
        CLASS_ADDED, CLASS_REMOVED, CLASS_MODIFIED,
        METHOD_ADDED, METHOD_REMOVED, METHOD_MODIFIED,
        FIELD_ADDED, FIELD_REMOVED, FIELD_MODIFIED
    }

    public ApiChange {
        oldParameterTypes = List.copyOf(oldParameterTypes);
    }
}