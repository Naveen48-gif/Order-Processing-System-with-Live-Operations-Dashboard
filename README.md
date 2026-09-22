# Order Processing System with Live Operations Dashboard

A backend-focused order processing system built using **Java and Spring Boot**. The project demonstrates concurrent order processing, inventory management, database transactions, retry handling, and real-time updates through a web dashboard.

The main goal of this project is to simulate how an e-commerce system can process multiple orders at the same time while preventing issues such as **overselling and negative inventory**.

## Features

* Concurrent order processing using Java thread pools
* Inventory management with database locking
* Prevention of negative inventory
* Order status tracking
* Automatic retry for temporary failures
* Dead Letter Queue (DLQ) for failed orders
* RabbitMQ for asynchronous order processing
* PostgreSQL/MySQL database support
* REST APIs for order and inventory operations
* Real-time updates using WebSocket
* React dashboard for monitoring orders and inventory
* Docker support for running PostgreSQL and RabbitMQ

## Technology Stack

### Backend

* Java
* Spring Boot
* Spring Data JPA
* Spring Web
* Spring WebSocket
* Spring AMQP
* Maven

### Database

* PostgreSQL
* MySQL 8
* H2 for testing

### Messaging

* RabbitMQ
* Dead Letter Queue (DLQ)

### Frontend

* React
* JavaScript
* WebSocket/STOMP

### Tools

* Git & GitHub
* Docker
* Postman
* IntelliJ IDEA / VS Code

## System Overview

The order processing flow is:

```text
Customer
   |
   v
REST API
   |
   v
Order Created (PENDING)
   |
   v
RabbitMQ Queue
   |
   v
Order Processor
   |
   +------> Check Inventory
   |             |
   |             v
   |       Database Lock
   |             |
   |       +-----+-----+
   |       |           |
   |   Available    Out of Stock
   |       |           |
   |       v           v
   |   Reduce Stock   Failed
   |       |
   |       v
   |    SUCCESS
   |
   v
WebSocket
   |
   v
Live Dashboard
```

## Concurrency and Inventory Handling

One of the main challenges in this project is handling multiple orders for the same product at the same time.

For example, if only **5 units** of a product are available and multiple customers place orders simultaneously, the system must make sure that the inventory does not become negative.

To handle this, the backend uses a **pessimistic database lock** while updating inventory.

```sql
SELECT *
FROM inventory
WHERE product_id = ?
FOR UPDATE;
```

The inventory update is performed inside a database transaction.

A database constraint is also used as an additional safety check:

```sql
CHECK (quantity >= 0)
```

This provides two levels of protection against negative inventory.

## Order Processing

Orders are initially created with the status:

```text
PENDING
```

They are then placed into the RabbitMQ queue for asynchronous processing.

Depending on the processing result, an order can move through states such as:

```text
PENDING
   |
   +----> PROCESSING
   |          |
   |          +----> COMPLETED
   |          |
   |          +----> FAILED
   |
   +----> OUT_OF_STOCK
```

## Retry Mechanism

Temporary technical failures can be retried a limited number of times.

For example:

```text
Order
  |
  v
Processing
  |
  +-- Temporary Error --> Retry
  |                         |
  |                         v
  |                    Processing Again
  |
  +-- Retry Limit Reached --> DLQ
```

Business failures such as insufficient inventory are not repeatedly retried.

Failed orders can be viewed from the dashboard.

## Dead Letter Queue

Orders that cannot be processed successfully after the configured retry attempts are sent to a **Dead Letter Queue (DLQ)**.

The DLQ helps separate failed orders from normal processing and makes it possible to inspect or retry them later.

## Real-Time Dashboard

The frontend dashboard displays information such as:

* Current orders
* Order status
* Product inventory
* Failed orders
* Dead Letter Queue orders
* Processing updates

Instead of continuously polling the backend, the dashboard receives updates using **WebSocket/STOMP**.

WebSocket endpoint:

```text
ws://localhost:8080/ws
```

## Project Structure

```text
Order-Processing-System-with-Live-Operations-Dashboard/
│
├── backend/
│   ├── src/
│   │   └── main/
│   │       └── java/
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   ├── package.json
│   └── ...
│
├── docker-compose.yml
├── .env.example
└── README.md
```

## How to Run

### 1. Start PostgreSQL and RabbitMQ

If Docker is installed:

```bash
docker compose up -d
```

This starts:

* PostgreSQL on port `5432`
* RabbitMQ on port `5672`
* RabbitMQ Management UI on port `15672`

### 2. Start the Backend

```bash
cd backend
mvn spring-boot:run
```

The backend will start at:

```text
http://localhost:8080
```

### 3. Start the Frontend

Open another terminal:

```bash
cd frontend
npm install
npm run dev
```

The frontend will normally be available at:

```text
http://localhost:5173
```

## Database Options

The project can be configured to use:

* PostgreSQL
* MySQL 8
* H2 for testing

For production deployment, PostgreSQL can also be hosted using services such as **AWS RDS**.

## Future Improvements

Some possible improvements for the project are:

* Authentication and authorization
* Better error handling and validation
* Order history and analytics
* User management
* Improved dashboard visualizations
* Docker-based complete deployment
* Cloud deployment
* Monitoring and logging
* Automated testing
* Performance testing with a large number of concurrent orders

## Learning Outcomes

Through this project, we explored:

* Java multithreading and concurrency
* Spring Boot REST API development
* Database transactions and locking
* RabbitMQ messaging
* Retry mechanisms
* Dead Letter Queues
* WebSocket communication
* React frontend development
* PostgreSQL database management
* Docker-based development

## Team Project

This project was developed as a student team project to understand how backend systems handle **concurrent requests, inventory consistency, asynchronous processing, and real-time monitoring**.

---

**Built with Java, Spring Boot, RabbitMQ, PostgreSQL, React, and Docker.**
