package io.hashmatrix.controlplane.tenant.web.dto;

import io.hashmatrix.controlplane.tenant.domain.Tenant;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 租户目录分页视图（契约 {@code openapi/control-plane-v1} 的 {@code TenantList}）。
 *
 * <p>{@code page}/{@code pageSize} 回显<b>请求</b>的页码与页长（钳制后值，1 起算），{@code total} 为
 * 满足过滤条件的总条数；{@code items} 为本页 {@link TenantView} 列表。供 webui admin 渲染分页与
 * 待审队列（取代 M1 临时的前端全量客户端过滤）。
 */
public record TenantListView(List<TenantView> items, int page, int pageSize, long total) {

    /** 由 Spring Data {@link Page} 装配；{@code page}/{@code pageSize} 取请求侧钳制后值（非 0 起算的内部页号）。 */
    public static TenantListView from(Page<Tenant> page, int requestedPage, int requestedPageSize) {
        return new TenantListView(
                page.getContent().stream().map(TenantView::from).toList(),
                requestedPage,
                requestedPageSize,
                page.getTotalElements());
    }
}
