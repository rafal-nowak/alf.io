/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.test.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures the events logged by one class, for tests that check what is logged. Close it to detach it.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final AbstractAppender appender;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LogManager.getLogger(type);
        this.previousLevel = logger.getLevel();
        this.appender = new AbstractAppender("capture-" + type.getSimpleName(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    /**
     * @return the formatted messages logged at exactly {@code level}
     */
    public List<String> messages(Level level) {
        return events.stream()
            .filter(event -> event.getLevel() == level)
            .map(event -> event.getMessage().getFormattedMessage())
            .toList();
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
