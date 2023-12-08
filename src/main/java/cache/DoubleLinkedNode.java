package cache;

import lombok.Getter;
import lombok.Setter;

public class DoubleLinkedNode<X> {
    @Getter
    @Setter
    private DoubleLinkedNode<X> older;
    @Getter
    @Setter
    private DoubleLinkedNode<X> newer;
    @Getter
    private final X value;

    private DoubleLinkedNode(DoubleLinkedNode<X> older, DoubleLinkedNode<X> newer, X value) {
        this.older = older;
        this.newer = newer;
        this.value = value;
    }

    public static <X> DoubleLinkedNode<X> middleNode(DoubleLinkedNode<X> older, DoubleLinkedNode<X> newer, X value) {
        return new DoubleLinkedNode<>(older, newer, value);
    }

    public static <X> DoubleLinkedNode<X> onlyNode(X value) {
        return new DoubleLinkedNode<>(null, null, value);
    }

    public static <X> DoubleLinkedNode<X> newestNode(DoubleLinkedNode<X> older, X value) {
        return new DoubleLinkedNode<>(older, null, value);
    }
}
