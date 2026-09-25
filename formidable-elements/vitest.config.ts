import path from 'node:path';
import {defineConfig} from 'vitest/config';

// Standalone config: vitest must NOT pick up vite.config.mjs, whose
// module-federation plugin spawns watchers that never exit under vitest.
// The `~` alias is the one vite.config.mjs and tsconfig.json declare, repeated
// here so that a hook or component module resolves under test as it does in the build.
export default defineConfig({
    resolve: {
        alias: {'~': path.resolve('./src')}
    },
    test: {
        include: ['src/**/*.test.ts'],
        environment: 'node'
    }
});
