// Replaces the `@stylex` directive in app/globals.css with the atomic CSS the
// Babel plugin collected. The plugin options are repeated here rather than
// imported from the Babel config, because Next's Babel loader refuses a .cjs
// config and package.json says "type": "module" — so the Babel half has to live
// in .babelrc, which is JSON and cannot be imported back.
module.exports = {
  plugins: {
    '@stylexjs/postcss-plugin': {
      include: [
        'app/**/*.{js,jsx,ts,tsx}',
        'components/**/*.{js,jsx,ts,tsx}',
        'lib/**/*.{js,jsx,ts,tsx}',
      ],
      babelConfig: {
        babelrc: false,
        parserOpts: { plugins: ['typescript', 'jsx'] },
        plugins: [
          [
            '@stylexjs/babel-plugin',
            {
              runtimeInjection: false,
              enableInlinedConditionalMerge: true,
              treeshakeCompensation: true,
              unstable_moduleResolution: { type: 'commonJS' },
            },
          ],
        ],
      },
      useCSSLayers: true,
    },
    autoprefixer: {},
  },
};
