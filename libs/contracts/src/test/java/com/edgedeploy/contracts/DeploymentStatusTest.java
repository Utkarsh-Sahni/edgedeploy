package com.edgedeploy.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.edgedeploy.contracts.DeploymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class DeploymentStatusTest {

    @Test
    void happyPathIsAValidChain() {
        DeploymentStatus[] chain = {QUEUED, BUILDING, IMAGE_BUILT, PUSHING, DEPLOYING, HEALTH_CHECK, RUNNING, STOPPED};
        for (int i = 0; i < chain.length - 1; i++) {
            assertThat(chain[i].canTransitionTo(chain[i + 1]))
                    .as("%s -> %s", chain[i], chain[i + 1])
                    .isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStatus.class, names = {"QUEUED", "BUILDING", "IMAGE_BUILT", "PUSHING", "DEPLOYING", "HEALTH_CHECK"})
    void anyPreRunningStateCanFail(DeploymentStatus status) {
        assertThat(status.canTransitionTo(FAILED)).isTrue();
    }

    @Test
    void stagesCannotBeSkippedOrReversed() {
        assertThat(QUEUED.canTransitionTo(RUNNING)).isFalse();
        assertThat(BUILDING.canTransitionTo(DEPLOYING)).isFalse();
        assertThat(RUNNING.canTransitionTo(BUILDING)).isFalse();
        assertThat(RUNNING.canTransitionTo(FAILED)).isFalse();
    }

    @Test
    void remoteDeploymentsPushStraightAfterBuildingWhileLocalOnesEndAtImageBuilt() {
        DeploymentStatus[] aws = {QUEUED, BUILDING, PUSHING, DEPLOYING, HEALTH_CHECK, RUNNING};
        for (int i = 0; i < aws.length - 1; i++) {
            assertThat(aws[i].canTransitionTo(aws[i + 1])).as("%s -> %s", aws[i], aws[i + 1]).isTrue();
        }
        assertThat(BUILDING.canTransitionTo(IMAGE_BUILT)).isTrue();
        assertThat(PUSHING.canTransitionTo(RUNNING)).isFalse();
    }

    @Test
    void cancellingIsOnlyAllowedBeforeTheRolloutStarts() {
        assertThat(QUEUED.isCancellable()).isTrue();
        assertThat(BUILDING.isCancellable()).isTrue();
        assertThat(PUSHING.isCancellable()).isTrue();
        assertThat(DEPLOYING.isCancellable()).isFalse();
        assertThat(HEALTH_CHECK.isCancellable()).isFalse();
        assertThat(RUNNING.isCancellable()).isFalse();
    }

    @Test
    void imageBuiltIsAResultNotWorkInProgress() {
        assertThat(IMAGE_BUILT.isSettled()).isTrue();
        assertThat(IMAGE_BUILT.isInProgress()).isFalse();
        assertThat(IMAGE_BUILT.isTerminal()).isFalse();
    }

    @Test
    void terminalStatesHaveNoTransitions() {
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(STOPPED.isTerminal()).isTrue();
        assertThat(RUNNING.isTerminal()).isFalse();
        for (DeploymentStatus target : DeploymentStatus.values()) {
            assertThat(FAILED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void noStateTransitionsToItself() {
        for (DeploymentStatus status : DeploymentStatus.values()) {
            assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
        }
    }
}
