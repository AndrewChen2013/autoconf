package me.config.watcher;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.sun.nio.file.SensitivityWatchEventModifier;
import me.config.api.IFileListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collection;
import java.util.Map;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 监测本地目录的文件变更
 * Created by lirui on 2015-09-29 14:38.
 */
public class FileUpdateWatcher implements Runnable {
  /**
   * 同一个目录下会包含多个文件,每个文件又有多个listener
   */
  private final Map<Path, Multimap<Path, IFileListener>> watches = Maps.newConcurrentMap();
  private final Map<WatchKey, Path> keys = Maps.newConcurrentMap();
  private final Map<Path, Long> masks = Maps.newConcurrentMap();
  private Logger log = LoggerFactory.getLogger(getClass());
  private WatchService watchService;
  private boolean running = false;

  private FileUpdateWatcher() {
    try {
      watchService = FileSystems.getDefault().newWatchService();
    } catch (IOException e) {
      log.error("cannot build watchService", e);
    }
  }

  public static FileUpdateWatcher getInstance() {
    return LazyHolder.instance;
  }

  public void shutdown() {
    running = false;
  }

  public void mask(Path path) {
    masks.put(path, System.currentTimeMillis());
  }

  public FileUpdateWatcher watch(Path path, IFileListener listener) {
    Path parent = path.getParent();
    Multimap<Path, IFileListener> files = watches.get(parent);
    if (files == null) {
      try {
        WatchEvent.Kind[] events = {ENTRY_MODIFY, ENTRY_DELETE};
        WatchKey key = parent.register(watchService, events, SensitivityWatchEventModifier.HIGH);
        keys.put(key, parent);
        log.info("monitor directory {}", parent);
      } catch (IOException e) {
        log.error("cannot register path:{}", parent, e);
      }
      files = ArrayListMultimap.create();
      watches.put(parent, files);
    }
    log.debug("watch {}, {}", path, listener);
    files.put(path, listener);
    return this;
  }

  public void start() {
    if (!running) {
      Thread t = new Thread(this, "LocalFileUpdateWatcher");
      t.setDaemon(true);
      t.start();
    }
  }

  @Override
  public void run() {
    running = true;
    while (running) {
      WatchKey key = null;
      try {
        key = watchService.take();
        if (key != null) {
          Path base = keys.get(key);
          for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind kind = event.kind();
            if (kind == OVERFLOW) {
              continue;
            }
            WatchEvent<Path> ev = cast(event);
            Path context = ev.context();
            Path child = base.resolve(context);
            log.info("{}, {}", kind, child);
            //屏蔽只剩小10秒钟,避免误封禁
            Long stamp = masks.remove(child);
            if (stamp != null && System.currentTimeMillis() - stamp < 3000) {
              log.info("mask {}", child);
              continue;
            }

            //屏蔽一会,避免频繁加载
            mask(child);
            Collection<IFileListener> listeners = watches.get(base).get(child);
            if (listeners == null || listeners.size() == 0) {
              continue;
            }
            //配置文件内容都不大,所以这里就读出来,不用每个listener再分别读取了
            byte[] content = new byte[0];
            if (child.toFile().exists()) {
              content = Files.readAllBytes(child);
            }
            for (IFileListener i : listeners) {
              i.changed(child, content);
            }
          }
        }
      } catch (InterruptedException x) {
        log.error("{} was interrupted, now EXIT", Thread.currentThread().getName());
        break;
      } catch (Exception e) {
        log.error("watches: {}", watches.keySet(), e);
      } finally {
        if (key != null) {
          key.reset();
        }
      }
    }
    running = false;
    try {
      watchService.close();
    } catch (IOException ignored) {
    }
  }

  @SuppressWarnings("unchecked")
  private <T> WatchEvent<T> cast(WatchEvent<?> event) {
    return (WatchEvent<T>) event;
  }

  private static final class LazyHolder {
    private static final FileUpdateWatcher instance = create();

    private static FileUpdateWatcher create() {
      FileUpdateWatcher watcher = new FileUpdateWatcher();
      watcher.start();
      return watcher;
    }
  }
}
