package nl.yogh.aerius.server.collections;

import java.util.HashMap;

public abstract class AutoFillingDictionary<K, V> extends HashMap<K, V> {
  private static final long serialVersionUID = -4040982204816673692L;

  protected abstract V createEmptyRecord(K key);

  @Override
  @SuppressWarnings("unchecked")
  public V get(final Object key) {
    if (!containsKey(key)) {
      put((K) key, createEmptyRecord((K) key));
    }

    return super.get(key);
  }
}
