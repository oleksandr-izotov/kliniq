import { apiRequest } from '../../auth/api';
import type { Schemas } from '../index';

/**
 * The backend emits before/after/metadata as raw JSON via
 * `@JsonRawValue`, so they arrive on the wire as objects, not strings.
 * The OpenAPI codegen sees the field type as String (the Kotlin field's
 * static type), so we override here to match runtime reality.
 */
export type AuditEventDto = Omit<Schemas['AuditEventDto'], 'before' | 'after' | 'metadata'> & {
	readonly before?: unknown;
	readonly after?: unknown;
	readonly metadata?: unknown;
};

export type AuditEventPageDto = Omit<Schemas['AuditEventPageDto'], 'items'> & {
	readonly items: readonly AuditEventDto[];
};

export interface AuditEventFilter {
	entityType?: string;
	actorUserId?: string;
	action?: string;
	/** ISO-8601 with offset; inclusive lower bound on createdAt. */
	from?: string;
	/** ISO-8601 with offset; exclusive upper bound on createdAt. */
	to?: string;
	page?: number;
	pageSize?: number;
}

function buildQuery(filter: AuditEventFilter): string {
	const params = new URLSearchParams();
	if (filter.entityType) params.set('entityType', filter.entityType);
	if (filter.actorUserId) params.set('actorUserId', filter.actorUserId);
	if (filter.action) params.set('action', filter.action);
	if (filter.from) params.set('from', filter.from);
	if (filter.to) params.set('to', filter.to);
	if (filter.page !== undefined) params.set('page', String(filter.page));
	if (filter.pageSize !== undefined) params.set('pageSize', String(filter.pageSize));
	const qs = params.toString();
	return qs ? `?${qs}` : '';
}

export const adminAuditApi = {
	list(filter: AuditEventFilter = {}): Promise<AuditEventPageDto> {
		return apiRequest<AuditEventPageDto>('GET', `/api/v1/admin/audit${buildQuery(filter)}`, {});
	}
};
