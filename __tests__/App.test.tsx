/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';

jest.mock('react-native/Libraries/BatchedBridge/NativeModules', () => {
  const nativeModules = jest.requireActual('@react-native/jest-preset/jest/mocks/NativeModules').default;
  const jieliModule = {
    addListener: jest.fn(),
    removeListeners: jest.fn(),
    setAncMode: jest.fn(),
    setDeviceName: jest.fn(),
    setEqMode: jest.fn(),
    setGameMode: jest.fn(),
    startScan: jest.fn(),
    stopScan: jest.fn(),
  };
  return {__esModule: true, default: {...nativeModules, JieliModule: jieliModule}};
});

import App, {buildDeviceName, utf8ByteLength, validateOwnerName} from '../App';

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});

test('builds the mandatory owner and product name format', () => {
  expect(buildDeviceName('  Tawanda  ', 'ZenVibe 2')).toBe("Tawanda's ZenVibe 2");
});

test('validates empty, invalid-whitespace, and overlong names', () => {
  expect(validateOwnerName('', 'ZenVibe 2')).toBe('Enter your name.');
  expect(validateOwnerName('Tawanda\n', 'ZenVibe 2')).toBe('Use letters and numbers only.');
  expect(validateOwnerName('Tawanda-1', 'ZenVibe 2')).toBe('Use letters and numbers only.');
  expect(validateOwnerName('a'.repeat(20), 'ZenVibe 2')).toBe('That name is too long for the earbuds.');
  expect(validateOwnerName('Tawanda', 'ZenVibe 2')).toBeNull();
});

test('counts UTF-8 bytes rather than JavaScript characters', () => {
  expect(utf8ByteLength('Tawanda')).toBe(7);
  expect(utf8ByteLength('é')).toBe(2);
  expect(utf8ByteLength('🎧')).toBe(4);
});
