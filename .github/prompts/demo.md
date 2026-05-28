Implementa la funcionalidad para crear cuentas bancarias (ahorros o corriente) en el `accounts-service`. Sigue buenas practicas de la industria. Toca solo este servicio, nada mas. Comunicate con el agente `microservice-bank 1` que esta trabajando en el `api-gateway` para alinear el contrato del API.


Hay otro agente implementando la creacion de cuentas bancarias en el `accounts-service`. Tu trabajo es hacer los cambios necesarios en el `api-gateway` para exponer esa funcionalidad. Comunicate con ese agente `microservice-bank` para pasarle la informacion que haga falta sobre la implementation en el `api-gateway`

Agrega el botón de crear nueva cuenta bancaria en la vista. Solo modifica el UI. Hay otros dos agentes trabajando en el `api-gateway` y `accounts-service`. Comunicate con ellos para alinear cambios. 