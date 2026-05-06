/**
 * Public re-exports from `generated.ts` (which is itself produced by
 * `pnpm gen:api` and SHOULD NOT be edited by hand).
 *
 * Components/schemas in OpenAPI map to wire types; pulling them out as
 * `Schemas` saves callers from typing `components['schemas']['Foo']`
 * everywhere.
 */
import type { components, paths } from './generated';

export type Schemas = components['schemas'];
export type Paths = paths;
