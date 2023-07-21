package kafka.log.remote;

import org.apache.kafka.common.TopicPartition;

public class RemoteLogManagerMBean {
    final RemoteLogManager remoteLogManager;

    public RemoteLogManagerMBean(RemoteLogManager remoteLogManager) {
        this.remoteLogManager = remoteLogManager;
    }

    public void pauseTasks(String topicName) {
        remoteLogManager.leaderOrFollowerTasks
                .entrySet()
                .stream()
                .filter(e -> e.getKey().topicPartition().topic().equals(topicName))
                .forEach(e -> e.getValue().pause());
    }

    public void pauseTasks(TopicPartition topicPartition) {
        remoteLogManager.leaderOrFollowerTasks
                .entrySet()
                .stream()
                .filter(e -> e.getKey().topicPartition().topic().equals(topicPartition.topic()) &&
                        e.getKey().topicPartition().partition() == topicPartition.partition())
                .forEach(e -> e.getValue().pause());
    }

    public void pauseTasks() {
        remoteLogManager.leaderOrFollowerTasks
                .forEach((topicIdPartition, rlmTaskWithFuture) -> rlmTaskWithFuture.pause());
    }

    public void resumeTasks(String topicName) {
        remoteLogManager.leaderOrFollowerTasks
                .entrySet()
                .stream()
                .filter(e -> e.getKey().topicPartition().topic().equals(topicName))
                .forEach(e -> e.getValue().pause());
    }

    public void resumeTasks(TopicPartition topicPartition) {
        remoteLogManager.leaderOrFollowerTasks
                .entrySet()
                .stream()
                .filter(e -> e.getKey().topicPartition().topic().equals(topicPartition.topic()) &&
                        e.getKey().topicPartition().partition() == topicPartition.partition())
                .forEach(e -> e.getValue().resume());
    }

    public void resumeTasks() {
        remoteLogManager.leaderOrFollowerTasks
                .forEach((topicIdPartition, rlmTaskWithFuture) -> rlmTaskWithFuture.resume());
    }
}
