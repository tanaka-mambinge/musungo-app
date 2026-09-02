const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const path = require('node:path');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const defaultConfig = getDefaultConfig(__dirname);
const config = {
  resolver: {
    sourceExts: [...defaultConfig.resolver.sourceExts, 'mjs'],
    resolveRequest: (context, moduleName, platform) => {
      if (moduleName === '@tabler/icons-react-native') {
        return {
          type: 'sourceFile',
          filePath: path.resolve(
            __dirname,
            'node_modules/@tabler/icons-react-native/dist/esm/tabler-icons-react-native.mjs',
          ),
        };
      }
      if (moduleName.startsWith('@tabler/icons-react-native/')) {
        const iconName = moduleName.split('/')[2];
        return {
          type: 'sourceFile',
          filePath: path.resolve(
            __dirname,
            `node_modules/@tabler/icons-react-native/dist/esm/icons/${iconName}.mjs`,
          ),
        };
      }
      return context.resolveRequest(context, moduleName, platform);
    },
  },
};

module.exports = mergeConfig(defaultConfig, config);
