package io.jexxa.tutorials.bookstore.domainservice;

import io.jexxa.core.JexxaMain;
import io.jexxa.jexxatest.JexxaTest;
import io.jexxa.tutorials.bookstore.BookStore;
import io.jexxa.tutorials.bookstore.domain.book.BookSoldOut;
import io.jexxa.tutorials.bookstore.domain.book.ISBN13;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.jexxa.tutorials.bookstore.domain.book.BookSoldOut.bookSoldOut;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainEventPublisherImplTest
{
    //Declare the packages that should be used by Jexxa
    private static final String DRIVEN_ADAPTER  = BookStore.class.getPackageName() + ".infrastructure.drivenadapter";
    private static final String OUTBOUND_PORTS  = BookStore.class.getPackageName() + ".domainservice";

    private JexxaTest jexxaTest;
    private DomainEventPublisher objectUnderTest;

    @BeforeEach
    void initTest()
    {
        var jexxaMain = new JexxaMain(getClass());
        jexxaMain
                .addToApplicationCore(OUTBOUND_PORTS)
                .addToInfrastructure(DRIVEN_ADAPTER);

        jexxaTest = new JexxaTest(jexxaMain);
        objectUnderTest = jexxaTest.getInstanceOfPort(DomainEventPublisher.class);
    }

    @Test
    void testDomainEvent()
    {
        // Arrange
        var messageRecorder = jexxaTest.getMessageRecorder(DomainEventPublisher.class);
        var isbn13 = new ISBN13("978-3-86490-387-8");

        // Act
        objectUnderTest.publish(bookSoldOut(isbn13));

        // Assert
        assertDoesNotThrow(() -> messageRecorder.getMessage(BookSoldOut.class));
    }

    @Test
    void testInvalidDomainEvent()
    {
        // Arrange
        var isbn13 = new ISBN13("978-3-86490-387-8");

        // Act / Assert
        assertThrows(IllegalArgumentException.class, () -> objectUnderTest.publish(isbn13));
    }

}