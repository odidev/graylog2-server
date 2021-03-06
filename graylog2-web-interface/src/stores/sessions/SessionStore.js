import Reflux from 'reflux';

import Store from 'logic/local-storage/Store';
import { qualifyUrl } from 'util/URLUtils';
import ApiRoutes from 'routing/ApiRoutes';
import { Builder } from 'logic/rest/FetchProvider';
import ActionsProvider from 'injection/ActionsProvider';

const SessionActions = ActionsProvider.getActions('Session');

const SessionStore = Reflux.createStore({
  listenables: [SessionActions],
  sourceUrl: '/system/sessions',
  sessionId: undefined,
  username: undefined,
  validatingSession: false,

  init() {
    this.validate();
  },
  getInitialState() {
    return this.getSessionInfo();
  },

  login(username, password, host) {
    const builder = new Builder('POST', qualifyUrl(this.sourceUrl))
      .json({ username: username, password: password, host: host });
    const promise = builder.build()
      .then((sessionInfo) => {
        return { sessionId: sessionInfo.session_id, username: sessionInfo.username };
      });

    SessionActions.login.promise(promise);
  },
  logout(sessionId) {
    const promise = new Builder('DELETE', qualifyUrl(`${this.sourceUrl}/${sessionId}`))
      .authenticated()
      .build()
      .then((resp) => {
        if (resp.ok || resp.status === 401) {
          this._removeSession();
        }
      }, this._removeSession);

    SessionActions.logout.promise(promise);
  },

  validate() {
    const sessionId = Store.get('sessionId');
    const username = Store.get('username');

    this.validatingSession = true;
    this._propagateState();
    const promise = this._validateSession(sessionId)
      .then((response) => {
        if (response.is_valid) {
          return SessionActions.login.completed({
            sessionId: sessionId || response.session_id,
            username: username || response.username,
          });
        }

        if (sessionId && username) {
          this._removeSession();
        }

        return response;
      })
      .finally(() => {
        this.validatingSession = false;
        this._propagateState();
      });

    SessionActions.validate.promise(promise);
  },
  _validateSession(sessionId) {
    return new Builder('GET', qualifyUrl(ApiRoutes.SessionsApiController.validate().url))
      .session(sessionId)
      .json()
      .build();
  },

  _removeSession() {
    Store.delete('sessionId');
    Store.delete('username');
    this.sessionId = undefined;
    this.username = undefined;
    this._propagateState();
  },

  _propagateState() {
    this.trigger(this.getSessionInfo());
  },

  loginCompleted(sessionInfo) {
    Store.set('sessionId', sessionInfo.sessionId);
    Store.set('username', sessionInfo.username);
    this.sessionId = sessionInfo.sessionId;
    this.username = sessionInfo.username;
    this._propagateState();
  },
  isLoggedIn() {
    return this.sessionId !== undefined && this.sessionId !== null;
  },
  getSessionId() {
    return this.sessionId;
  },
  getSessionInfo() {
    return { sessionId: this.sessionId, username: this.username, validatingSession: this.validatingSession };
  },
});

export default SessionStore;
