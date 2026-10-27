import { Parameter } from '../domain/model/report';

const PARAMETER_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

// Matches the declared parameter's HTML input type (see index.pug, which renders
// `input(type=parameter.type, ...)` directly from this same value) against the submitted value.
// This only rejects clearly malformed input (an object/array where a scalar is expected, or a
// value that isn't even shaped like the declared type) - report parameters otherwise still go
// through ParameterCoercion.coerce on the executor side against the report's own compiled
// parameter types, which this frontend has no visibility into.
// A parameter with options (rendered as a `select` instead) only accepts one of its option
// values, or the empty "keine Auswahl" entry.
export function isValidParameterValue(declared: Parameter, value: unknown): boolean {
  if (value === null || typeof value === 'object') {
    return false;
  }
  const stringValue = String(value);
  if (declared.options?.length) {
    return stringValue === '' || declared.options.some((option) => option.value === stringValue);
  }
  switch (declared.type) {
    case 'number':
      return stringValue === '' || Number.isFinite(Number(stringValue));
    case 'date':
      return stringValue === '' || PARAMETER_DATE_PATTERN.test(stringValue);
    default:
      return true;
  }
}
