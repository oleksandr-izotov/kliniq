import { apiRequest } from '../../auth/api';
import type { Schemas } from '../index';

export type AdminUserDto = Schemas['AdminUserDto'];
export type AdminUserPageDto = Schemas['AdminUserPageDto'];
export type AdminUserRole = AdminUserDto['role'];
export type AdminUserStatus = AdminUserDto['status'];
export type AdminUserSpecialty = NonNullable<AdminUserDto['specialty']>;

/**
 * Backend treats every field as optional (Kotlin nullable defaults to
 * "no change" in the use case). The OpenAPI spec emits them as required
 * because of our --properties-required-by-default codegen flag, so we
 * peel that constraint off at this typed boundary.
 */
export type UpdateUserAdminRequest = Partial<Schemas['UpdateUserAdminRequest']>;

export interface AdminUserListFilter {
	q?: string;
	role?: AdminUserRole;
	isSurgeon?: boolean;
	status?: AdminUserStatus;
	page?: number;
	pageSize?: number;
}

function buildQuery(filter: AdminUserListFilter): string {
	const params = new URLSearchParams();
	if (filter.q) params.set('q', filter.q);
	if (filter.role) params.set('role', filter.role);
	if (filter.isSurgeon !== undefined) params.set('isSurgeon', String(filter.isSurgeon));
	if (filter.status) params.set('status', filter.status);
	if (filter.page !== undefined) params.set('page', String(filter.page));
	if (filter.pageSize !== undefined) params.set('pageSize', String(filter.pageSize));
	const qs = params.toString();
	return qs ? `?${qs}` : '';
}

export const adminUsersApi = {
	list(filter: AdminUserListFilter = {}): Promise<AdminUserPageDto> {
		return apiRequest<AdminUserPageDto>('GET', `/api/v1/admin/users${buildQuery(filter)}`, {});
	},

	update(id: string, patch: UpdateUserAdminRequest): Promise<AdminUserDto> {
		return apiRequest<AdminUserDto>('PATCH', `/api/v1/admin/users/${id}`, { body: patch });
	}
};
