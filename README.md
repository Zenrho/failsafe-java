# Zenrho Failsafe

A lightweight Java library for elegant exception handling and retry logic.

## Installation

### Maven
```xml
<dependency>
    <groupId>com.zenrho</groupId>
    <artifactId>failsafe</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle
```groovy
implementation 'com.zenrho:failsafe:1.0.0'
```

## Usage

### Basic Exception Handling

```java
Failsafe.run(() -> {
    // Your code here
    riskyOperation();
})
.onException(IOException.class)
    .retry(3)
    .and()
.onException(SQLException.class)
    .ignore()
.start();
```

### Advanced Features

#### Retry with Custom Logic
```java
Failsafe.run(() -> connectToDatabase())
    .onException(ConnectionException.class)
        .modify(() -> resetConnection())
        .retry(3)
        .and()
    .onSuccess(() -> log.info("Connected successfully"))
    .finallyDo(() -> cleanup())
    .start();
```

#### Handling Multiple Exceptions
```java
Failsafe.run(() -> {
    processData();
})
.onException(IOException.class)
    .retry(3)
    .and()
.onException(ValidationException.class)
    .modify(e -> logValidationError(e))
    .undo()
    .and()
.onException()  // Catches all other exceptions
    .ignore()
.start();
```

#### Iterating Over Collections
```java
List<User> users = getUserList();
Failsafe.iterate(users, user -> {
    processUser(user);
})
.onException(UserProcessingException.class)
    .modify(e -> logError(e))
    .retry(2)
.start();
```

## Key Features

1. **Fluent API**: Chain exception handling logic naturally
2. **Retry Mechanism**: Configurable retry attempts with custom logic
3. **Exception-Specific Handling**: Different strategies for different exceptions
4. **Pre/Post Actions**: Execute code before retries or after success
5. **Collection Processing**: Built-in support for iterating over collections safely

## Common Patterns

### Retry with Backoff
```java
Failsafe.run(() -> externalServiceCall())
    .onException(ServiceException.class)
        .modify(() -> Thread.sleep(1000)) // 1 second delay between retries
        .retry(3)
    .start();
```

### Transaction-like Behavior
```java
Failsafe.run(() -> saveData())
    .onException(DatabaseException.class)
        .modify(() -> rollback())
        .undo()
    .onSuccess(() -> commit())
    .start();
```

### Logging with Continuation
```java
Failsafe.run(() -> process())
    .onException(ProcessingException.class)
        .modify(e -> log.error("Processing failed", e))
        .retry(2)
    .onException()
        .modify(e -> log.error("Unexpected error", e))
        .ignore()
    .start();
```

## License

MIT