import { Parameter } from '../domain/model/report';
import { isValidParameterValue } from './parameter-validation';

function parameter(type: string, options?: Parameter['options']): Parameter {
  return { name: 'p', type, options };
}

const taetigkeit = parameter('select', [
  { value: '138', label: 'Landesbeauftragte*r' },
  { value: '176', label: 'AK Leiter*in' },
]);

test('rejects objects and arrays for any type', () => {
  expect(isValidParameterValue(parameter('text'), { a: 1 })).toBe(false);
  expect(isValidParameterValue(parameter('text'), ['a'])).toBe(false);
  expect(isValidParameterValue(parameter('text'), null)).toBe(false);
  expect(isValidParameterValue(taetigkeit, ['138'])).toBe(false);
});

test('accepts finite numbers or an empty value for type number', () => {
  expect(isValidParameterValue(parameter('number'), '42')).toBe(true);
  expect(isValidParameterValue(parameter('number'), '')).toBe(true);
  expect(isValidParameterValue(parameter('number'), 'abc')).toBe(false);
});

test('accepts ISO dates or an empty value for type date', () => {
  expect(isValidParameterValue(parameter('date'), '2021-01-01')).toBe(true);
  expect(isValidParameterValue(parameter('date'), '')).toBe(true);
  expect(isValidParameterValue(parameter('date'), '01.01.2021')).toBe(false);
});

test('accepts one of the declared option values for a parameter with options', () => {
  expect(isValidParameterValue(taetigkeit, '138')).toBe(true);
  expect(isValidParameterValue(taetigkeit, '176')).toBe(true);
});

test('accepts the empty "keine Auswahl" value for a parameter with options', () => {
  expect(isValidParameterValue(taetigkeit, '')).toBe(true);
});

test('rejects values that are not among the declared options', () => {
  expect(isValidParameterValue(taetigkeit, '999')).toBe(false);
  // the label is only for display - the report gets the option's value
  expect(isValidParameterValue(taetigkeit, 'AK Leiter*in')).toBe(false);
});

test('treats an empty options list like no options at all', () => {
  expect(isValidParameterValue(parameter('text', []), 'anything')).toBe(true);
});
