package io.hashmatrix.controlplane.provisioning.stub;

import io.hashmatrix.controlplane.tenant.member.OrgMember;
import io.hashmatrix.controlplane.tenant.member.OrgMemberDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存版组织成员目录 stub —— {@code identity!=keycloak}（默认）时装配，使无活 Keycloak 也能本地/集成跑通
 * 「列→加→移」时序。成员状态仅存进程内（按 orgId 分桶），重启即失。
 *
 * <p>与真实适配器的行为契约对齐：{@code addExistingUser} 幂等（同一 email 重复加入返回同一成员）、
 * {@code remove} 幂等（不存在静默跳过）。stub 不持有真实 realm，故把传入的 email/username 直接<b>视为</b>
 * 已存在用户合成成员（id = {@code stub-user-<email>}），不抛 {@code MemberUserNotFoundException}。
 */
public class InMemoryOrgMemberDirectory implements OrgMemberDirectory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOrgMemberDirectory.class);

    /** orgId → (memberId → 成员)。 */
    private final Map<String, Map<String, OrgMember>> byOrg = new ConcurrentHashMap<>();

    @Override
    public List<OrgMember> list(String orgId) {
        return new ArrayList<>(bucket(orgId).values());
    }

    @Override
    public OrgMember addExistingUser(String orgId, String emailOrUsername) {
        String memberId = "stub-user-" + emailOrUsername;
        OrgMember member = new OrgMember(memberId, emailOrUsername, emailOrUsername, true);
        bucket(orgId).putIfAbsent(memberId, member);
        log.info("[stub:member] 加成员 org={} member={}", orgId, memberId);
        return bucket(orgId).get(memberId);
    }

    @Override
    public void remove(String orgId, String memberId) {
        bucket(orgId).remove(memberId);
        log.info("[stub:member] 移除成员 org={} member={}", orgId, memberId);
    }

    private Map<String, OrgMember> bucket(String orgId) {
        return byOrg.computeIfAbsent(orgId, k -> new ConcurrentHashMap<>());
    }
}
