import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import StorefrontPage from './pages/StorefrontPage.jsx';
import AdminPage from './pages/AdminPage.jsx';
import './styles/App.css';

// Two separate routes, not a tab toggle on one page: a customer landing on "/" has no way to reach
// admin actions (catalogue management, restock, DLQ replay, load/fault drills) short of typing /admin.
export default function App() {
  return (
    <BrowserRouter>
      <header className="site-header">
        <h1>Order Management</h1>
        <nav className="nav-tabs">
          <NavLink to="/" end className={({ isActive }) => `nav-tab ${isActive ? 'active' : ''}`}>
            Storefront
          </NavLink>
          <NavLink to="/admin" className={({ isActive }) => `nav-tab ${isActive ? 'active' : ''}`}>
            Admin
          </NavLink>
        </nav>
      </header>
      <Routes>
        <Route path="/" element={<StorefrontPage />} />
        <Route path="/admin" element={<AdminPage />} />
      </Routes>
    </BrowserRouter>
  );
}

