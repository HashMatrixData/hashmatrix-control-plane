package io.hashmatrix.controlplane.provisioning;

/** 开通编排失败（某一步端口操作未成功）。携带失败的步骤名以便排障与回退。 */
public class ProvisioningException extends RuntimeException {

    private final String step;

    public ProvisioningException(String step, String message, Throwable cause) {
        super("开通步骤[" + step + "]失败：" + message, cause);
        this.step = step;
    }

    public String getStep() {
        return step;
    }
}
